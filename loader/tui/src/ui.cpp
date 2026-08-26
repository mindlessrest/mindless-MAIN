#include "ui.hpp"
#include "injection.hpp"
#include "process_list.hpp"
#include "progress.hpp"

#include <atomic>
#include <chrono>
#include <cmath>
#include <iostream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <ftxui/component/component.hpp>
#include <ftxui/component/screen_interactive.hpp>
#include <ftxui/dom/elements.hpp>

using namespace ftxui;

namespace mindless {

static const std::string ascii_art =
    " ┳┳┓•  ┓┓\n"
    " ┃┃┃┓┏┓┏┫┃┏┓┏┏\n"
    " ┛ ┗┗┛┗┗┻┗┗ ┛┛";

static std::vector<Element> render_ascii(const Color& c) {
    std::vector<Element> lines;
    std::istringstream ss(ascii_art);
    std::string line;
    while (std::getline(ss, line)) {
        lines.push_back(text(line) | color(c) | center);
    }
    return lines;
}

static Element sweep_bar(float elapsed, int width, const Color& track_col, const Color& accent_col) {
    float period = 1.6f;
    float phase = std::fmod(elapsed, period) / period;
    float t = phase < 0.5f
        ? 4.0f * phase * phase * phase
        : 1.0f - std::pow(-2.0f * phase + 2.0f, 3.0f) / 2.0f;

    int seg_w = width / 3;
    int seg_center = static_cast<int>((float)(width + seg_w) * t) - seg_w / 2;

    Elements chars;
    for (int i = 0; i < width; i++) {
        float d = std::abs(i - seg_center) / (float)(seg_w / 2);
        if (d < 1.0f) {
            float brightness = (1.0f - d * d) * (1.0f - d * d);
            int r = 40 + static_cast<int>(140 * brightness);
            int g = 35 + static_cast<int>(85 * brightness);
            int b = 50 + static_cast<int>(205 * brightness);
            chars.push_back(text("━") | color(Color::RGB(r, g, b)));
        } else {
            chars.push_back(text("━") | color(track_col));
        }
    }
    return hbox(std::move(chars));
}

enum class Screen { ProcessSelect, Loading, Done, Failed };

void run_ui() {
    std::cout << "\033]0;Mindless\007" << std::flush;

    auto screen = ScreenInteractive::Fullscreen();
    auto accent = Color::RGB(180, 120, 255);
    auto accent_bright = Color::RGB(220, 180, 255);
    auto label_color = Color::RGB(180, 160, 220);
    auto text_dim = Color::RGB(100, 95, 120);
    auto track_color = Color::RGB(40, 35, 50);
    auto bg = Color::RGB(12, 10, 16);

    Screen current_screen = Screen::ProcessSelect;
    std::vector<ProcessEntry> processes;
    int selected = 0;
    std::string error_msg;
    std::string status_text = "Connecting to Minecraft";
    std::atomic<bool> inject_done{false};
    std::atomic<bool> inject_ok{false};
    std::string inject_error;
    ProgressListener progress_listener;
    std::thread worker;
    std::atomic<int> countdown_val{3};
    std::mutex status_mutex;
    std::atomic<bool> running{true};

    auto start_time = std::chrono::steady_clock::now();

    // Smooth animation: post refresh events at 60fps
    std::thread refresh_thread([&] {
        while (running.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            screen.PostEvent(Event::Custom);
        }
    });

    processes = enumerate_java_processes();
    auto last_refresh = std::chrono::steady_clock::now();

    auto do_inject = [&] {
        if (processes.empty() || selected < 0 || selected >= (int)processes.size())
            return;

        current_screen = Screen::Loading;
        {
            std::lock_guard<std::mutex> lock(status_mutex);
            status_text = "Connecting to Minecraft";
        }

        progress_listener.start();

        worker = std::thread([&] {
            {
                std::lock_guard<std::mutex> lock(status_mutex);
                status_text = "Checking files";
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(400));

            std::string lib = extract_runtime();
            if (lib.empty()) {
                inject_error = "Could not verify Mindless files";
                inject_ok = false;
                inject_done = true;
                return;
            }

            {
                std::lock_guard<std::mutex> lock(status_mutex);
                status_text = "Loading Mindless";
            }

            InjectOptions opts{ processes[selected].pid, lib };
            std::string err;
            auto result = inject(opts, err);

            if (result == InjectResult::Ok || result == InjectResult::AlreadyLoaded) {
                {
                    std::lock_guard<std::mutex> lock(status_mutex);
                    status_text = "Starting Mindless";
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(800));
                inject_ok = true;
            } else {
                inject_ok = false;
                inject_error = err;
            }
            inject_done = true;

            if (inject_ok.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(600));
                for (int c = 3; c > 0; --c) {
                    countdown_val = c;
                    std::this_thread::sleep_for(std::chrono::seconds(1));
                }
                screen.ExitLoopClosure()();
            }
        });
    };

    auto menu_option = MenuOption::Vertical();
    menu_option.on_enter = do_inject;
    menu_option.entries_option.transform = [&](const EntryState& s) {
        auto e = text(s.label);
        if (s.focused) e |= bold | color(accent_bright);
        else e |= color(Color::RGB(180, 175, 195));
        return e;
    };

    std::vector<std::string> menu_entries;
    auto rebuild_entries = [&] {
        menu_entries.clear();
        for (auto& p : processes) {
            menu_entries.push_back("  " + p.title + "  (PID " + std::to_string(p.pid) + ")");
        }
        if (menu_entries.empty())
            menu_entries.push_back("  No Minecraft instances found");
    };
    rebuild_entries();

    auto menu = Menu(&menu_entries, &selected, menu_option);

    auto renderer = Renderer(menu, [&]() {
        auto header = vbox(render_ascii(accent));

        float elapsed = std::chrono::duration<float>(
            std::chrono::steady_clock::now() - start_time).count();

        if (current_screen == Screen::ProcessSelect) {
            auto now = std::chrono::steady_clock::now();
            if (now - last_refresh > std::chrono::seconds(2)) {
                last_refresh = now;
                processes = enumerate_java_processes();
                rebuild_entries();
            }

            return vbox({
                separatorEmpty() | size(HEIGHT, EQUAL, 2),
                header,
                separatorEmpty() | size(HEIGHT, EQUAL, 3),
                text(" Select Minecraft") | bold | color(label_color) | center,
                separatorEmpty(),
                menu->Render() | size(WIDTH, LESS_THAN, 55) | center,
                separatorEmpty() | size(HEIGHT, EQUAL, 2),
                hbox({
                    text("Enter") | bold | color(accent),
                    text(" Continue   ") | color(text_dim),
                    text("Esc") | bold | color(accent),
                    text(" Quit") | color(text_dim),
                }) | center,
            }) | center | bgcolor(bg);
        }

        if (current_screen == Screen::Loading) {
            std::string st;
            {
                std::lock_guard<std::mutex> lock(status_mutex);
                st = status_text;
            }

            std::string ps = progress_listener.status();
            if (!ps.empty()) {
                std::lock_guard<std::mutex> lock(status_mutex);
                status_text = ps;
                st = ps;
            }

            if (inject_done.load()) {
                if (inject_ok.load()) {
                    current_screen = Screen::Done;
                } else {
                    current_screen = Screen::Failed;
                    error_msg = inject_error;
                }
            }

            return vbox({
                separatorEmpty() | size(HEIGHT, EQUAL, 2),
                header,
                separatorEmpty() | size(HEIGHT, EQUAL, 4),
                text(st) | color(Color::RGB(210, 210, 220)) | center,
                separatorEmpty(),
                sweep_bar(elapsed, 44, track_color, accent) | center,
                separatorEmpty(),
                text("PID " + std::to_string(
                    (processes.empty() || selected < 0) ? 0 : processes[selected].pid
                )) | color(text_dim) | center,
            }) | center | bgcolor(bg);
        }

        if (current_screen == Screen::Done) {
            return vbox({
                separatorEmpty() | size(HEIGHT, EQUAL, 2),
                header,
                separatorEmpty() | size(HEIGHT, EQUAL, 4),
                text("Ready") | bold | color(Color::RGB(120, 255, 170)) | center,
                separatorEmpty(),
                hbox({
                    text("Closing in ") | color(text_dim),
                    text(std::to_string(countdown_val.load())) | bold | color(accent_bright),
                }) | center,
            }) | center | bgcolor(bg);
        }

        return vbox({
            separatorEmpty() | size(HEIGHT, EQUAL, 2),
            header,
            separatorEmpty() | size(HEIGHT, EQUAL, 4),
            text(error_msg) | bold | color(Color::RGB(255, 90, 90)) | center,
            separatorEmpty(),
            text("Keep Minecraft open and try again") | color(text_dim) | center,
            separatorEmpty() | size(HEIGHT, EQUAL, 2),
            hbox({
                text("R") | bold | color(accent),
                text(" Retry   ") | color(text_dim),
                text("Esc") | bold | color(accent),
                text(" Quit") | color(text_dim),
            }) | center,
        }) | center | bgcolor(bg);
    });

    renderer |= CatchEvent([&](Event event) {
        if (event == Event::Custom) return true;

        if (event == Event::Escape) {
            screen.ExitLoopClosure()();
            return true;
        }

        if (current_screen == Screen::Done) {
            screen.ExitLoopClosure()();
            return true;
        }

        if (current_screen == Screen::Failed) {
            if (event == Event::Character('r') || event == Event::Character('R')) {
                current_screen = Screen::ProcessSelect;
                inject_done = false;
                inject_ok = false;
                inject_error.clear();
                progress_listener.stop();
                if (worker.joinable()) worker.join();
                processes = enumerate_java_processes();
                rebuild_entries();
                return true;
            }
        }

        return false;
    });

    screen.Loop(renderer);

    running = false;
    refresh_thread.join();
    progress_listener.stop();
    if (worker.joinable()) worker.join();
}

} // namespace mindless
