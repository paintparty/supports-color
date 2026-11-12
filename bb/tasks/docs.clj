(ns tasks.docs
  "Helpers for the docs:* tasks in bb.edn."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn- sh
  "Runs a command and returns its trimmed stdout. Does not throw on failure."
  [& args]
  (-> (apply p/shell {:out :string :err :string :continue true} args)
      :out
      str/trim))

(defn- latest-tag []
  (not-empty (sh "git describe --tags --abbrev=0")))

(defn- lib-name
  "The lib symbol from `(def lib '...)` in build.clj, as a string."
  []
  (second (re-find #"[(]def lib '(\S+)[)]" (slurp "build.clj"))))

(defn- build-url
  "The project URL inferred from build.clj: the first `[:url \"...\"]` entry
   in the pom data, or else https://github.com/owner/repo from an
   io.github.owner/repo lib name."
  []
  (or (second (re-find #"\[:url \"(https?://[^\"]+)\"\]" (slurp "build.clj")))
      (when-let [[_ owner repo] (re-find #"^io[.]github[.]([^/]+)/(.+)$" (lib-name))]
        (str "https://github.com/" owner "/" repo))))

(defn- repo-url
  "The repo URL from `git remote get-url origin`, or from build.clj when
   there is no origin remote."
  []
  (if-let [remote (not-empty (sh "git remote get-url origin"))]
    ;; git@github.com:owner/repo.git -> https://github.com/owner/repo
    (-> remote
        (str/replace #"^git@github[.]com:" "https://github.com/")
        (str/replace #"[.]git$" ""))
    (build-url)))

(defn update-readme
  "Updates the version numbers in README.md to match the latest tag
   (v0.2.0 -> 0.2.0). Leaves README.md alone if there are no tags yet."
  []
  (if-let [tag (latest-tag)]
    ;; Rewrites the deps.edn and Leiningen coordinates in the Setup section
    (let [version (str/replace tag #"^v" "")
          q       (java.util.regex.Pattern/quote (lib-name))]
      (spit "README.md"
            (-> (slurp "README.md")
                (str/replace (re-pattern (str "(" q " [{]:mvn/version \")[^\"]+(\"[}])"))
                             (str "$1" version "$2"))
                (str/replace (re-pattern (str "([\\[]" q " \")[^\"]+(\"[\\]])"))
                             (str "$1" version "$2"))))
      (println "Updated README.md to version" version))
    (println "No tags yet, so README.md is unchanged. Rerun after tagging a release.")))

(defn write-git-deps
  "Regenerates docs/git-deps.md from docs/git-deps.template.md and the
   latest tag. Uses HEAD if there are no tags yet."
  []
  (let [tag     (latest-tag)
        sha     (sh "git rev-list -n 1" (or tag "HEAD"))
        url     (repo-url)
        project (last (str/split url #"/"))]
    (spit "docs/git-deps.md"
          (-> (slurp "docs/git-deps.template.md")
              (str/replace "{{PROJECT}}" project)
              (str/replace "{{LIB}}" (lib-name))
              (str/replace "{{URL}}" (str "\"" url "\""))
              (str/replace "{{TAG}}" (str "\"" (or tag "<no tag yet>") "\""))
              (str/replace "{{SHA}}" (str "\"" sha "\""))))
    (when-not tag
      (println "No tags yet, so the SHA is from HEAD. Rerun after tagging a release."))
    (println "Wrote docs/git-deps.md for" (or tag "HEAD") sha)))
