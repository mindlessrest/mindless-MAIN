\# MindlessLoader — Agent Instructions



\## Project



\*\*MindlessLoader\*\* is a lightweight Windows desktop loader written in modern C++.



The project uses a custom D3D11 UI renderer and FreeType for text rendering.



The visual goal is:



> Minimal, polished, modern loader UI. High quality without unnecessary complexity.



Mindless is \*\*not\*\* intended to look like a generic "hacker" interface or an oversized game cheat menu.



\---



\## Core Stack



\* C++

\* C++20 minimum

\* CMake

\* Ninja

\* Visual Studio as the IDE

\* LLVM/Clang toolchain

\* Direct3D 11

\* Win32

\* FreeType

\* Git



Do not introduce Qt.



Do not introduce Dear ImGui.



Do not introduce DirectWrite.



Do not introduce another UI framework unless explicitly requested.



Keep dependencies minimal.



\---



\## Compiler



The preferred compiler is LLVM/Clang on Windows.



Do not silently switch the project to MSVC just because Visual Studio is being used.



Visual Studio is the IDE/build environment; the project should remain compatible with the configured LLVM toolchain.



Do not modify compiler/toolchain configuration without a reason.



\---



\# Architecture



Keep the project modular but small.



Prefer simple components such as:



```text

src/

├── app/

├── platform/

├── renderer/

├── ui/

├── font/

└── process/

```



Do not create abstractions merely for the sake of abstraction.



A 30-line solution is preferable to a 300-line framework if both solve the problem correctly.



Avoid:



\* giant manager classes

\* unnecessary factories

\* singleton abuse

\* excessive inheritance

\* generic "utility" classes

\* speculative abstractions

\* over-engineering



Write code that a human C++ developer can understand quickly.



\---



\# Naming Convention



Use \*\*PascalCase\*\* for types:



```cpp

class FontAtlas;

class UiContext;

class Renderer;

struct ProcessInfo;

```



Use \*\*camelCase\*\* for functions and local variables:



```cpp

createWindow();

renderFrame();

processCount;

selectedProcess;

```



Use \*\*snake\_case\*\* only when required by an external API.



Use `PascalCase` for namespaces only when appropriate; prefer lowercase namespaces:



```cpp

namespace mindless

{

}

```



Private member variables use a trailing underscore:



```cpp

class Renderer

{

public:

&#x20;   void render();



private:

&#x20;   ID3D11Device\* device\_;

&#x20;   ID3D11DeviceContext\* context\_;

};

```



Constants should be descriptive:



```cpp

constexpr int defaultWidth = 720;

constexpr int defaultHeight = 480;

```



Avoid prefixes such as:



```text

m\_

g\_

p\_

lp\_

```



unless required for Windows/API interoperability.



\---



\# Code Style



Write \*\*human C++\*\*.



Prefer:



```cpp

if (!device\_)

&#x20;   return false;

```



over unnecessary abstraction.



Prefer straightforward control flow.



Avoid deeply nested code.



Avoid comments that merely translate the code into English.



Bad:



```cpp

// Set device to nullptr

device\_ = nullptr;

```



Good:



```cpp

// Device is owned by the renderer lifetime.

device\_ = nullptr;

```



Only comment when the reasoning is useful.



Do not add comments explaining obvious C++.



\---



\# Memory Management



Prefer RAII.



Use appropriate smart pointers and wrappers where ownership matters.



For COM objects, use the project's existing COM ownership approach consistently.



Do not introduce manual `new`/`delete` without a specific reason.



Avoid ownership ambiguity.



Every resource should have an obvious owner.



\---



\# UI



Mindless uses a \*\*custom immediate-style UI/draw-list architecture\*\*.



The UI should remain small and composable.



Widgets should be easy to create.



The architecture should allow things conceptually similar to:



```cpp

ui.text("Mindless");

ui.button("Launch");

ui.panel(...);

ui.progressBar(...);

```



without exposing D3D11 or FreeType internals to every widget.



Widgets should generate rendering commands through the UI/draw-list layer.



Do not make widgets directly manage global renderer state unless absolutely necessary.



\---



\# Draw List



The draw list should remain simple.



It may contain commands conceptually such as:



```text

rectangle

rounded rectangle

line

image

text

```



The UI generates commands.



The renderer consumes commands.



Keep this separation clear:



```text

UI

&#x20;↓

DrawList

&#x20;↓

D3D11 Renderer

```



Do not make every widget call raw D3D11 functions.



\---



\# Rendering



Use Direct3D 11.



The renderer should avoid unnecessary resource creation during every frame.



Do not recreate:



\* D3D11 device

\* swap chain

\* font atlas

\* shaders

\* vertex buffers

\* textures



every frame unless the resource genuinely requires dynamic recreation.



Reuse GPU resources.



Keep the render loop predictable:



```text

Input

&#x20;↓

UI update

&#x20;↓

Clear

&#x20;↓

Generate draw list

&#x20;↓

Render draw list

&#x20;↓

Present

```



Do not present multiple times per frame.



\---



\# Window



Use a native Win32 window.



Mindless should be \*\*borderless\*\*.



Do not use the default Windows title bar as part of the visual design.



Do not use:



```text

WS\_CAPTION

```



or similar styles simply to obtain default window chrome.



The custom UI owns the visual title area.



The application should not visibly flash a default Windows window during startup.



Initialize the window and renderer correctly before displaying the first usable frame.



\---



\# Font Rendering



Use \*\*FreeType\*\*.



Do not use DirectWrite.



The font system is responsible for:



\* font loading

\* glyph rasterization

\* glyph metrics

\* atlas generation

\* glyph lookup

\* text measurement



The UI should not know about FreeType internals.



Correctly handle:



```text

bitmap\_left

bitmap\_top

bitmap.width

bitmap.rows

advance.x

advance.y

```



Do not assume glyphs start at `(0, 0)`.



Text must use proper baseline positioning.



Do not rasterize tiny glyphs and upscale them.



Text should remain sharp at normal UI sizes.



\---



\# Colors



Use hexadecimal color notation throughout UI code.



Examples:



```cpp

constexpr Color background = 0x101014;

constexpr Color foreground = 0xF5F5F7;

constexpr Color muted = 0x8E8E93;

constexpr Color accent = 0xFFFFFF;

```



Do not randomly mix unrelated color representations throughout the UI.



Keep the palette centralized where practical.



\---



\# Visual Design



The UI should be:



\* minimal

\* clean

\* restrained

\* modern

\* spacious

\* polished



Avoid:



\* neon colors

\* excessive glow

\* fake "hacker" aesthetics

\* giant cards

\* excessive gradients

\* unnecessary borders

\* visual clutter

\* generic cheat-menu styling



Mindless is a \*\*software loader\*\*.



The UI should communicate that immediately.



\---



\# Minecraft Process Selection



The Minecraft selection screen should remain simple.



Discover actual Windows processes.



Look for:



```text

javaw.exe

```



Multiple matching processes should be displayed separately.



Display only useful information, such as:



```text

Minecraft

javaw.exe

PID 12345

```



If no matching process exists:



```text

Minecraft not found

```



Do not fabricate real processes.



Test/demo processes must never be presented as genuine processes in production behavior.



Selection state must persist correctly between frames.



Hover and selected states should be subtle.



\---



\# Animation



Animations should be:



\* subtle

\* smooth

\* purposeful



Use delta time.



Do not implement animations with:



```cpp

Sleep(...)

```



or blocking delays in the UI/render thread.



Never block the render loop to create an animation.



Avoid animations that exist purely because "modern UI needs animation."



\---



\# Performance



Do not optimize blindly.



First understand the bottleneck.



Avoid:



\* allocations every frame where unnecessary

\* rebuilding static GPU resources

\* rebuilding the font atlas every frame

\* repeated process enumeration every render frame

\* unnecessary texture uploads

\* unnecessary window recreation



The UI should remain lightweight.



\---



\# Error Handling



Handle failures explicitly.



For example:



```cpp

if (!device\_)

{

&#x20;   return false;

}

```



Do not silently ignore important initialization failures.



Do not spam message boxes for ordinary errors.



Use the existing logging/error mechanism.



\---



\# CMake



CMake is the source of truth for project configuration.



Do not manually create Visual Studio project files.



Do not add generated build files to Git.



Build output belongs in:



```text

out/

```



Do not commit:



```text

.vs/

out/

\*.obj

\*.pdb

\*.ilk

\*.exe

```



unless explicitly required.



Keep `CMakeLists.txt` readable.



Prefer modern CMake target-based configuration.



\---



\# Git



Use Git continuously.



Before making changes:



```bash

git status

```



Inspect the existing state before modifying files.



Make small, logical commits.



Use conventional commit prefixes:



```text

feat:

fix:

refactor:

perf:

docs:

chore:

build:

```



Examples:



```text

feat: add font atlas

fix: correct glyph positioning

fix: prevent startup window flash

fix: stabilize ui state

refactor: simplify process selector

perf: reuse font gpu resources

build: configure freetype dependency

```



Do not create commits such as:



```text

update

stuff

changes

final

final2

test

lol

```



Do not mix unrelated changes into one commit.



Do not rewrite Git history unless explicitly requested.



Do not use destructive commands such as:



```bash

git reset --hard

git clean -fd

```



without explicit permission.



\---



\# Before Editing



Always inspect the relevant existing implementation first.



Do not assume the architecture.



Do not rewrite a subsystem simply because you would have designed it differently.



Prefer incremental improvements.



If a bug can be fixed with a 20-line change, do not rewrite 500 lines.



\---



\# Before Finishing



Build the project.



At minimum verify:



```text

CMake configure

Debug build

Release build

```



Fix compiler warnings introduced by your changes.



If a test or manual verification is relevant, perform it.



Review the final diff:



```bash

git diff

git status

```



Make sure no unrelated files were modified.



\---



\# Important Rule



\*\*Do not make the project more complicated than it needs to be.\*\*



Mindless should feel like it was written by a skilled C++ developer who values control, performance, readability, and visual polish.



When choosing between:



```text

more abstraction

```



and:



```text

simple code that works

```



prefer the simple code.



When choosing between:



```text

more dependencies

```



and:



```text

a small custom implementation

```



prefer the small implementation when it is reasonable.



When uncertain, inspect the existing code and preserve its conventions rather than inventing new ones.



\---



\# Comments



Do not add comments to code.



The code should be self-explanatory through clear naming and structure.



Do not add:



```cpp

// Initialize renderer

initializeRenderer();



// Draw button

drawButton();



// Check if process exists

if (processExists)

Do not add comments explaining obvious implementation details.

Do not add TODO comments.

Do not add decorative section comments.

Do not add comments simply to make code look documented.

Only use comments if they are absolutely required to explain something that cannot reasonably be expressed through code structure or naming.

If an existing comment is unnecessary and you are already modifying that code, remove it.

Prefer clean code over commented code.


> **No comments unless the comment explains a non-obvious external constraint, compiler/ABI requirement, or genuinely unintuitive piece of behavior.**
