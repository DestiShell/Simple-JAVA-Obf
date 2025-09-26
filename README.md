# Jar Obfuscator

A small Java-based JAR obfuscator that uses ASM to rename classes, fields and methods inside a JAR. The tool is designed to be simple and easy to use for small projects and learning purposes.

> **Warning:** This obfuscator performs simple name remapping and file renaming. It is not a commercial-grade obfuscator — it does not perform control-flow obfuscation, string encryption, or advanced anti-decompilation techniques. Use at your own risk and always keep backups of original binaries.

---

## Features

* Scans a JAR and collects class, field and method names using ASM.
* Generates random short names for classes, fields and methods (with simple exclusions).
* Remaps bytecode using `ClassRemapper` / `SimpleRemapper` from ASM.
* Renames `.class` files to their obfuscated internal names.
* Preserves and updates `Main-Class` and some `Rsrc-*` manifest attributes when possible.
* Skips obfuscation for Java core packages and configurable resource loader classes.

## Quick Usage

1. Build the project (example with Gradle or javac — build system not included here):

```bash
# Example (if you package into a fat jar)
# javac -cp asm.jar:asm-commons.jar -d out src/obf/swag/*.java
# jar cf obfuscator.jar -C out .

java -jar obfuscator.jar path/to/your.jar
```

2. Output file will be created next to the original with `_obfuscated.jar` suffix (e.g. `myapp_obfuscated.jar`).

Example:

```bash
java -jar obfuscator.jar myapp.jar
# -> myapp_obfuscated.jar
```

## What the tool does

* Extracts the JAR to a temporary directory.
* Reads `META-INF/MANIFEST.MF` and tries to preserve or update `Main-Class`, `Rsrc-Main-Class` and `Rsrc-Class-Path` attributes.
* Collects class names, fields and methods using a small ASM `ClassVisitor` (`ClassInfoCollector`).
* Generates new random names for classes/fields/methods, but keeps certain names unchanged (e.g. `main`, constructors, `serialVersionUID`, `toString`).
* Applies remapping with `ClassRemapper` and writes obfuscated class bytes back to disk.
* Renames class files to match new internal names, then repackages the JAR.

## Important implementation details

* Random name generation uses a restricted charset and prefixes (`c`, `f`, `m`) to avoid starting names with digits.
* Inner classes are handled by reusing the renamed outer class name and appending the original `$...` suffix.
* The tool attempts to preserve resource loader classes (e.g. names that contain `RsrcLoader` or `cfg3wgjn5gc`) and avoids obfuscating JDK internal packages (java/, javax/, com/sun/, sun/).
* Methods are stored with their descriptors (`name + descriptor`) but only the method *name* portion is remapped — descriptors remain intact to avoid breaking signatures.

## Limitations & Caveats

* This obfuscator **does not** update non-class resources that reference class names (e.g. text config files, reflection-based lookups in resources) except for basic manifest attributes.
* It may break code that relies on reflection, serialization, or stringly-typed class names.
* It does not handle complex cases like invokedynamic name rewriting, module-info, or constant pool edge cases beyond what ASM remapping handles.
* Use on third-party libraries may violate licenses — make sure you have rights to modify the JAR.
* Not safe for production-level protection. For real protection consider a commercial obfuscator.

## Configuration / Customization ideas

* Allow command-line flags to control which packages to skip or include.
* Persist the mapping file to allow deterministic builds or deobfuscation later.
* Add options to preserve public API for libraries (e.g. keep public/protected names for external use).
* Add string encryption, flow obfuscation, resource renaming, and mapping export.

## Building

This repository contains plain Java source code that depends on **ASM** (asm, asm-commons). Use your preferred build tool (Gradle, Maven, or a manual `javac` compile) and include ASM on the classpath.

Minimal `javac` example:

```bash
# compile (example assumes asm jars are in libs/)
javac -cp "libs/asm.jar:libs/asm-commons.jar" -d out src/obf/swag/*.java
jar cf obfuscator.jar -C out .
```

## Example output

After obfuscation the tool prints mapping lines for classes/fields/methods and indicates the new `Main-Class` or `Rsrc-Main-Class` used in the generated manifest.

## License

This README and the example source are provided under the MIT License — adapt as you need. If you include third-party libraries (ASM), follow their licenses.

## Contributions

Contributions and improvements are welcome. Please open issues or pull requests on the repository.

