# Repository Guidelines

## Project Structure & Module Organization
MCEF is a Minecraft 1.19.2 mod that uses the MultiLoader layout with shared logic under `common` and loader-specific wrappers under `fabric` and `neoforge`. Place shared Java sources in `common/src/main/java` and assets such as menu JSON, translations, or textures in `common/src/main/resources` so they ship with every loader build. Loader-only hooks belong inside each module's `src/main/java` tree; keep local run directories like `run_client` and `run_server` for iterative testing but never depend on them for assets.

## Coding Style & Naming Conventions
Target Java 21 with 4-space indentation and UTF-8 encoding (WITHOUT BOM), matching the Gradle toolchain configuration. 

## Mixin Structurization
- Place shared mixins under `common/src/main/java/com/cinemamod/mcef/mixins`.
- Declare `@Mixin` classes (and accessor interfaces) with imports grouped at the top, list `@Unique` members before any `@Shadow` declarations, and extend or implement the vanilla type when necessary; supply a suppressed dummy constructor when subclasses require it.
- Suffix every unique field or helper with `_MCEF`. Static finals use all caps with `_MCEF`, and injected method names follow the `before/after/on/wrap/cancel_<VanillaMethod>_MCEF` pattern. Accessor/invoker methods also end in `_MCEF`.
- Cluster related injections together and keep helper wrappers private unless a wider contract is required.
- Use short `//` comments for quick reminders and `/** @reason ... */` blocks ahead of injections that change vanilla behaviour, matching the authoring tone in existing files.
- FancyMenu has access to Mixin Extras.
- Prefer using features from Mixin Extras instead of using normal Mixin redirects or overrides.
- When leveraging Mixin Extras (`WrapOperation`, `WrapWithCondition`, etc.), name helpers after the intent (`wrap_..._MCEF`, `cancel_..._MCEF`) and call the provided `Operation` when returning to vanilla flow.
- 
## Minecraft Sources
You have access to the full Minecraft 1.19.2 sources in the `minecraft_cached_sources` folder. The folder contains source sets for Fabric (`fabric`) and Forge (`forge`). Before starting a task, make sure to read sources you could need for the task, so you know how the current Minecraft code actually looks. Always do that, knowing how the actual Minecraft code looks is very important, especially when you work with mixins.
Make sure to always compare Vanilla classes from all 2 modloaders (Fabric, Forge), since Forge often alters Vanilla classes, so mixins can't always get applied in `common` and instead need to get implemented for every launcher if the point to place the mixin differs between modloaders.

## OpenGL Sources
You have access to the full LWJGL OpenGL library sources of the OpenGL version used in Minecraft 1.19.2 in the `library_cached_sources` folder. Make sure to check the sources when working on GUI tasks that involve working with raw OpenGL.

## Git & Run/Compile
NEVER try to run git commands or try to run/compile the project!