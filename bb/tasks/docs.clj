(ns tasks.docs
  "Helpers for the tag-release task in bb.edn."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
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
  "Regenerates the git deps doc at out-path (docs/git-deps.md, for example)
   from bb/tasks/git-deps.template.md and the latest tag. Uses HEAD if there
   are no tags yet."
  [out-path]
  (let [tag     (latest-tag)
        sha     (sh "git rev-list -n 1" (or tag "HEAD"))
        url     (repo-url)
        project (last (str/split url #"/"))]
    (fs/create-dirs (fs/parent out-path))
    (spit out-path
          (-> (slurp "bb/tasks/git-deps.template.md")
              (str/replace "{{PROJECT}}" project)
              (str/replace "{{LIB}}" (lib-name))
              (str/replace "{{URL}}" (str "\"" url "\""))
              (str/replace "{{TAG}}" (str "\"" (or tag "<no tag yet>") "\""))
              (str/replace "{{SHA}}" (str "\"" sha "\""))))
    (when-not tag
      (println "No tags yet, so the SHA is from HEAD. Rerun after tagging a release."))
    (println "Wrote" out-path "for" (or tag "HEAD") sha)))

(defn- build-version
  "The version string from `(def version \"...\")` in build.clj."
  []
  (second (re-find #"[(]def version \"([^\"]+)\"[)]" (slurp "build.clj"))))

(defn- changelog-section
  "The body of the `## <version>` section of CHANGELOG.md, without leading
   or trailing blank lines. The section ends at the next level-2 heading, or
   at the end of the file when it is the last section. Returns nil if there
   is no such heading or the body is blank."
  [version]
  (let [heading (str "## " version)]
    (->> (str/split-lines (slurp "CHANGELOG.md"))
         (drop-while #(not= heading (str/trimr %)))
         rest
         (take-while #(not (re-find #"^## " %)))
         (str/join "\n")
         str/trim
         not-empty)))

(defn tag-release
  "Creates annotated tag v<version>, with the version from build.clj and the
   matching CHANGELOG.md section as the tag message. Then updates the
   version numbers in README.md and regenerates doc/git-deps.md if it
   exists. Does not push or commit."
  []
  (let [version (build-version)
        tag     (str "v" version)]
    (when-not version
      (throw (ex-info "No (def version \"...\") found in build.clj." {})))
    (when (not-empty (sh "git status --porcelain"))
      (throw (ex-info "The working tree has uncommitted changes. Commit them first, since the tag goes on HEAD." {})))
    (when (not-empty (sh "git rev-parse -q --verify" (str "refs/tags/" tag)))
      (throw (ex-info (str "Tag " tag " already exists.") {:tag tag})))
    (let [section (changelog-section version)
          _       (when-not section
                    (throw (ex-info (str "CHANGELOG.md has no \"## " version "\" section, or the section is empty.")
                                    {:version version})))
          message (str tag "\n\n" section "\n")
          tmp     (fs/create-temp-file {:prefix "tag-message" :suffix ".txt"})]
      (println (str "Tag message:\n\n" message))
      (try
        (spit (str tmp) message)
        ;; verbatim keeps lines starting with #, such as ### Added
        (p/shell "git tag -a" tag "--cleanup=verbatim" "-F" (str tmp))
        (finally (fs/delete-if-exists tmp)))
      (println "Created tag" tag "on" (sh "git rev-parse --short HEAD"))
      (update-readme)
      (let [git-deps? (fs/exists? "doc/git-deps.md")]
        (if git-deps?
          (write-git-deps "doc/git-deps.md")
          (println "No doc/git-deps.md, so skipped it."))
        (println (str "\nNext: review and commit README.md"
                      (when git-deps? " and doc/git-deps.md")
                      ", then run:\n"
                      "git push origin " tag))))))
