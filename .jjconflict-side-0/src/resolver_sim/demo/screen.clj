(ns resolver-sim.demo.screen
  "Terminal screenshot renderer.

   Generates SVG terminal screenshots from captured command output.
   Zero external dependencies — pure Clojure SVG generation.

   Also produces asciicast v2 format for use with asciinema players."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [resolver-sim.config.defaults :as defaults]))

;; ── Constants ───────────────────────────────────────────────────────────────

(def term-font-family "Menlo, Monaco, 'Courier New', monospace")
(def term-font-size (defaults/default [:demo :term-font-size] 13))
(def term-line-height (defaults/default [:demo :term-line-height] 20))
(def term-pad-x (defaults/default [:demo :term-pad-x] 16))
(def term-pad-y (defaults/default [:demo :term-pad-y] 12))
(def term-bg "#1e1e2e")
(def term-fg "#cdd6f4")
(def term-title-bg "#181825")
(def term-title-fg "#a6adc8")
(def term-accent "#f38ba8")

;; ── SVG generation ──────────────────────────────────────────────────────────

(defn- escape-xml
  "Escape special XML chars in a string."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- line->svg
  "Render one line of terminal text as SVG."
  [line y]
  (let [escaped (escape-xml line)]
    (str "<text x=\"" term-pad-x "\" y=\"" y "\" "
         "font-family=\"" term-font-family "\" "
         "font-size=\"" term-font-size "\" "
         "fill=\"" term-fg "\">"
         (if (empty? escaped) "&#160;" escaped)
         "</text>")))

(defn- title-bar-svg
  "Render the terminal title bar (close/minimize buttons + label)."
  [width label]
  (let [btn-y 8
        btn-r 5
        bw (float width)]
    (str "<rect x=\"0\" y=\"0\" width=\"" bw "\" height=\"28\" fill=\"" term-title-bg "\" rx=\"6\"/>"
         "<circle cx=\"16\" cy=\"" btn-y "\" r=\"" btn-r "\" fill=\"" term-accent "\"/>"
         "<circle cx=\"30\" cy=\"" btn-y "\" r=\"" btn-r "\" fill=\"#f9e2af\"/>"
         "<circle cx=\"44\" cy=\"" btn-y "\" r=\"" btn-r "\" fill=\"#a6e3a1\"/>"
         "<text x=\"58\" y=\"17\" font-family=\"" term-font-family "\" "
         "font-size=\"11\" fill=\"" term-title-fg "\">"
         (escape-xml label) "</text>")))

(defn text->svg
  "Generate an SVG terminal screenshot from plain text.
   Options:
     :title   — terminal title bar label (default \"demo\")
     :width   — SVG width in px (default 800)
     :height  — SVG height in px, auto-calculated if nil
     :max-lines — max lines to render (default 500)"
  ([text] (text->svg text {}))
  ([text {:keys [title width height max-lines]
          :or   {title "demo" width 800 max-lines 500}}]
   (let [lines (take max-lines (str/split-lines text))
         n (count lines)
         h (or height (+ term-pad-y 28 term-pad-y (* n term-line-height) term-pad-y))
         body (str/join "\n" (map-indexed
                              (fn [i line]
                                (line->svg line
                                           (+ term-pad-y 28 term-pad-y (* i term-line-height))))
                              lines))
         title-svg (title-bar-svg width title)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          "<svg xmlns=\"http://www.w3.org/2000/svg\" "
          "width=\"" width "\" height=\"" h "\" "
          "viewBox=\"0 0 " width " " h "\">"
          "<defs><style>text{font-family:" term-font-family ";"
          "font-size:" term-font-size "px;}</style></defs>"
          "<rect x=\"0\" y=\"0\" width=\"" width "\" height=\"" h "\" "
          "fill=\"" term-bg "\" rx=\"6\"/>"
          title-svg
          body
          "</svg>"))))

;; ── Asciicast v2 generation ──────────────────────────────────────────────

(defn text->asciicast-str
  "Generate an asciicast v2 format string (NDJSON) from captured text.
   asciicast v2 uses newline-delimited JSON: line 1 is the header,
   subsequent lines are events arrays.
   Compatible with asciinema 2.x players."
  ([text] (text->asciicast-str text {:duration 5.0}))
  ([text {:keys [duration title command]
          :or   {duration 5.0 title "demo" command ""}}]
   (let [lines (str/split-lines text)
         n (count lines)
         delay-per-line (if (pos? n) (/ (float duration) n) 0.1)
         header {"version" 2
                 "width" 80
                 "height" (max 10 (min 500 n))
                 "env" {"SHELL" "/bin/bash" "TERM" "xterm-256color"}}
         header-line (json/write-str header)
         event-lines (str/join "\n"
                               (map (fn [i line]
                                      (json/write-str [(float (* i delay-per-line))
                                                       "o"
                                                       (str line "\n")]))
                                    (range) lines))]
     (str header-line "\n" event-lines "\n"))))

;; ── File output ──────────────────────────────────────────────────────────────

(defn render-screenshot!
  "Render captured command text as terminal screenshot SVG.
   Writes to output-dir/screenshots/<section-id>-<n>.svg
   Returns {:svg-path path :asciicast-path path}."
  ([output-dir section-id n text]
   (render-screenshot! output-dir section-id n text {}))
  ([output-dir section-id n text opts]
   (let [screen-dir (str output-dir "/screenshots")
         _ (.mkdirs (io/file screen-dir))
         svg (text->svg text opts)
         svg-path (str screen-dir "/" section-id "-" n ".svg")
         _ (spit svg-path svg)
         cast-str (text->asciicast-str text
                                       {:title (or (:title opts) section-id)
                                        :command (:command opts "")})
         cast-path (str screen-dir "/" section-id "-" n ".cast")
         _ (spit cast-path cast-str)]
     {:svg-path svg-path
      :asciicast-path cast-path})))
