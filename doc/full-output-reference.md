# Full output reference

The complete map each function returns, shown for a truecolor, dark terminal (Ghostty, in this example).

`:env` holds the 28 keys from `sc/env-var-keys` and nothing else. Each is the
variable's string value, or `nil` when unset. `:runtime :argv` holds only the color
flags detection recognized, such as `["--color=256"]`.

`supports-color.core/profile`:

```clojure
(sc/profile)
=>
{:level         3
 :color-profile :truecolor
 :runtime       {:host  :jvm
                 :os    {:name "Mac OS X" :version "15.5" :platform :macos}
                 :tty   {:stdout true :stderr true :stdin true}
                 :argv  []
                 :flags {:sniff-flags true :matched #{} :level nil}}
 :env           {"APPVEYOR"             nil
                 "BUILDKITE"            nil
                 "CI"                   nil
                 "CI_NAME"              nil
                 "CIRCLECI"             nil
                 "DRONE"                nil
                 "GITEA_ACTIONS"        nil
                 "GITHUB_ACTIONS"       nil
                 "GITLAB_CI"            nil
                 "TEAMCITY_VERSION"     nil
                 "TF_BUILD"             nil
                 "TRAVIS"               nil
                 "CLICOLOR"             nil
                 "CLICOLOR_FORCE"       nil
                 "CLITHEME"             nil
                 "COLORFGBG"            nil
                 "COLORTERM"            "truecolor"
                 "COLOR_BACKGROUND"     nil
                 "COLOR_CONTRAST"       nil
                 "COLOR_FOREGROUND"     nil
                 "COLOR_THEME"          nil
                 "FORCE_COLOR"          nil
                 "NO_COLOR"             nil
                 "TERM"                 "xterm-256color"
                 "TERM_PROGRAM"         "ghostty"
                 "TERM_PROGRAM_VERSION" "1.1.0"
                 "AGENT_NAME"           nil
                 "SUPPORTS_COLOR_NO_QUERY" nil}}
```

`supports-color.core/background`:

```clojure
(sc/background)
=>
{:polarity          "dark"
 :background-color  {:r 30 :g 30 :b 46}
 :detection-method  :osc11
 :clitheme          nil
 :csi996-result     nil
 :csi996-supported  false
 :osc11-supported   true
 :runtime           {:host  :jvm
                     :os    {:name "Mac OS X" :version "15.5" :platform :macos}
                     :tty   {:stdout true :stderr true :stdin true}
                     :argv  []
                     :flags {:sniff-flags true :matched #{} :level nil}}
 :env               {"APPVEYOR"             nil
                     "BUILDKITE"            nil
                     "CI"                   nil
                     "CI_NAME"              nil
                     "CIRCLECI"             nil
                     "DRONE"                nil
                     "GITEA_ACTIONS"        nil
                     "GITHUB_ACTIONS"       nil
                     "GITLAB_CI"            nil
                     "TEAMCITY_VERSION"     nil
                     "TF_BUILD"             nil
                     "TRAVIS"               nil
                     "CLICOLOR"             nil
                     "CLICOLOR_FORCE"       nil
                     "CLITHEME"             nil
                     "COLORFGBG"            nil
                     "COLORTERM"            "truecolor"
                     "COLOR_BACKGROUND"     nil
                     "COLOR_CONTRAST"       nil
                     "COLOR_FOREGROUND"     nil
                     "COLOR_THEME"          nil
                     "FORCE_COLOR"          nil
                     "NO_COLOR"             nil
                     "TERM"                 "xterm-256color"
                     "TERM_PROGRAM"         "ghostty"
                     "TERM_PROGRAM_VERSION" "1.1.0"
                     "AGENT_NAME"           nil
                     "SUPPORTS_COLOR_NO_QUERY" nil}}
```
