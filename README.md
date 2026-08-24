# mindless-MAIN

monorepo for the mindless client + loader

---

## whats in here

```
client/   minecraft 1.8.9 forge utility mod (~130 modules, mixin + transformer based)
loader/   c++ windows loader that injects the client into lunar client
tools/    build scripts - build.py builds both projects and finds every compiler path itself,
          msbt.py compiles a single .java script
build.bat run this if you dont wanna touch a terminal, handles everything
compile-script.bat  drag a .java script onto this to compile it
```

---

## how to build

just run `build.bat` - it checks for python, installs it if missing, then runs the build

or if you wanna be specific

```
python tools/build.py            # builds everything
python tools/build.py --loader   # loader only
python tools/build.py --client   # client only
```

output exe lands at the root of this folder

---

## what you need

- **loader** — llvm/clang, cmake, ninja, vcpkg (tools/build.py finds these automatically)
- **client** — jdk 17 (tools/build.py finds this too)

if something isnt found itll tell you exactly what to grab

---

## how injection works

loader finds your lunar process → extracts injector + native dll to temp → injects dll into jvm → dll loads the client jar → java bootstraps everything

progress is streamed back to the loader in realtime over a named pipe so you see whats actually happening

when its done the loader shrinks away and you get a windows notification
