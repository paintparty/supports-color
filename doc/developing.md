# Developing

## Running tests

```
$ bb test              # Clojure tests
$ bb test:bb           # Babashka tests
$ bb test:jolt         # full suite under jolt
$ bb test:jolt-basic   # runtime-agnostic detection tests only, under jolt
$ bb test:node         # ClojureScript tests under Node (nbb)
$ bb test:node-pty     # pty-driven Node query tests (needs node + python3 + a pty)
$ bb test:all          # Clojure + Babashka + jolt + Node
$ bb test:all+node-pty # test:all plus the pty-driven Node query tests
$ bb test:compat       # JVM suite against the oldest supported Clojure (1.10, 1.11)
```

<br />

## Build and release

Run the CI pipeline and build a JAR:

```
$ bb ci
```

This produces an updated `pom.xml` (synchronized dependencies, under `target/classes/META-INF`) and the JAR in `target`. Update the version and SCM tag in `build.clj`.

Install it locally (requires `ci` to have run first):

```
$ bb install
```

Run CI and deploy to Clojars (needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment variables):

```
$ bb ci:deploy
```

Deploys to `io.github.paintparty/supports-color` on clojars.org.

<br />

## Regenerating git-deps.md

This doc needs to be regenerated after a new release is tagged. Run `bb docs:git-deps`, commit regenerate `git-deps.md` and push to `main`. \[TODO - automate this].
