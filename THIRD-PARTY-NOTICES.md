# Third-party notices

kik-cc-python itself is licensed under the GNU LGPL v3 ([LICENSE](LICENSE), [COPYING](COPYING)).

The all-in-one JAR (`kik-cc-python-<version>-aio.jar`) bundles the following components, each under its own
license. The licenses below are the ones declared in the components' Maven POMs.

| Component | Maven coordinates | License |
|---|---|---|
| GraalPy (language and resources, including the Python standard library) | `org.graalvm.python:python-language`, `python-resources`, `python-community` | Universal Permissive License 1.0, MIT, Python Software Foundation License |
| GraalVM Polyglot API, Truffle, Truffle runtime and compiler, TRegex | `org.graalvm.polyglot:polyglot`, `org.graalvm.truffle:*`, `org.graalvm.regex:regex` | Universal Permissive License 1.0 |
| GraalVM SDK (collections, nativeimage, word, jniutils) | `org.graalvm.sdk:*` | Universal Permissive License 1.0 |
| Shadowed XZ and JSON | `org.graalvm.shadowed:xz`, `org.graalvm.shadowed:json` | Universal Permissive License 1.0 |
| Shadowed ICU4J | `org.graalvm.shadowed:icu4j` | Unicode/ICU License |
| GraalVM profiler tool | `org.graalvm.tools:profiler-tool` | GNU GPL v2 with the Classpath Exception |
| SQLite JDBC driver (includes SQLite, public domain) | `org.xerial:sqlite-jdbc` | Apache License 2.0 |

Not bundled, needed at runtime: Minecraft, Fabric Loader or NeoForge, and
[CC: Tweaked](https://github.com/cc-tweaked/CC-Tweaked).
