# Late replies over slow links

Why `background` waits up to 2000 ms for the terminal by default, and what goes wrong when a reply arrives too late.

## What `background` does

1. Your program calls `background` to learn whether the terminal is dark or light.
2. The library switches the terminal into a quiet mode, where typed bytes are not shown on screen.
3. It sends the terminal a question: "what's your background color?"
4. The terminal sends back an answer, a short string of bytes like `ESC]11;rgb:1e1e/1e1e/1e1e`. It arrives as if someone had typed it.
5. The library reads that answer, then switches the terminal back to normal mode.

The library does not wait forever. If no answer comes within `:timeout-ms`, it gives up and switches the terminal back to normal.

## What goes wrong when the answer is late

1. You are on SSH to a server far away, and each round trip takes 200 ms. The question travels to your terminal and the answer travels back, 200 ms in all.
2. Say the timeout is 100 ms. At 100 ms the library gives up and switches the terminal back to normal mode.
3. At 200 ms the answer arrives. The terminal is in normal mode now, so it treats those bytes as typing:
   - They appear on screen as garbage, like `^[]11;rgb:1e1e/1e1e/1e1e^[\`.
   - They sit in the input. The next time your program asks the user something ("Continue? [y/n]"), it reads that garbage first. If the program has already exited, the shell gets it on its command line.
4. `background` also returns "don't know", although the terminal did answer, just too late.

This matters because it is what users see first. A CLI that prints garbage and messes up their prompt looks broken, and the cause is hard to trace back to this library. Round trips over 100 ms are common, for example from home to a cloud server in another region.

## Why a long timeout is nearly free

The library also asks a second, standard question, DA1 (`ESC[c`), which almost every terminal answers, and it asks it last. Terminals answer in order, so when the DA1 answer arrives, everything before it has arrived too, and the library stops at once.

On a normal local terminal that takes a few milliseconds, whatever the timeout is. The timeout only runs out in full on a terminal that answers nothing at all. Every terminal checked so far answers DA1: Terminal.app, iTerm2, VS Code, Alacritty, Ghostty, WezTerm, GNU screen, and SSH sessions.

So the default is 2000 ms (it was 100 ms). On a terminal that answers, this costs nothing. On a slow link, it waits long enough. On a terminal that answers nothing, each `background` call takes 2 s instead of 0.1 s. termenv, a Go library that does the same query, waits up to 5 s for the same reason.

## How long an answer takes over SSH

Measured 2026-11-11 with a simulated delay on an SSH link (Ghostty on macOS, SSH into a Debian machine, `tc netem`):

| Network round trip | Bare question, no library | supports-color | Result with a 1000 ms timeout |
|---|---|---|---|
| none | | 35 to 125 ms | answered |
| 300 ms | 637 ms | 660 to 760 ms | answered |
| 500 ms | | gave up at 1029 ms | answer leaked onto the command line |

An answer took about two network round trips, with or without the library, so the extra time comes from the SSH link, not from this library. With the 2000 ms default, round trips up to about 900 ms are covered. That includes ordinary SSH across continents (usually 150 to 300 ms) and most satellite links.

## The grace period

Sometimes part of the answer has arrived by the deadline, but not all of it. Instead of cutting off mid-answer and leaving the rest to spill onto the screen, the library waits one more `:timeout-ms` for the rest. A call can therefore take up to twice `:timeout-ms`, but only when a terminal starts answering and then stalls.

## What is still not covered

An answer slower than `:timeout-ms` still leaks, as above. Over SSH that means a round trip longer than about half of `:timeout-ms`. If your users work over very slow links:

- raise `:timeout-ms`, up to 30000, or
- have them set `SUPPORTS_COLOR_NO_QUERY=1`, which skips the question entirely. Polarity then comes only from `CLITHEME` and `COLORFGBG`.
