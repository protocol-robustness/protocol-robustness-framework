📖 Book of Clerk
⚖️ Rationale

Computational notebooks allow arguing from evidence by mixing prose with executable code. For a good overview of problems users encounter in traditional notebooks like Jupyter, see I don't like notebooks and What’s Wrong with Computational Notebooks? Pain Points, Needs, and Design Opportunities.

Specifically Clerk wants to address the following problems:

    Less helpful than my editor
    Notebook code being hard to reuse
    Reproduction problems coming from out-of-order execution
    Problems with archival and putting notebooks in source control

Clerk is a notebook library for Clojure that aims to address these problems by doing less, namely:

    no editing environment, folks can keep using the editors they know and love
    no new format: Clerk notebooks are either regular Clojure namespaces (interspersed with markdown comments) or regular markdown files (interspersed with Clojure code fences). This also means Clerk notebooks are meant to be stored in source control.
    no out-of-order execution: Clerk notebooks always evaluate from top to bottom. Clerk builds a dependency graph of Clojure vars and only recomputes the needed changes to keep the feedback loop fast.
    no external process: Clerk runs inside your Clojure process, giving Clerk access to all code on the classpath.

🚀 Getting Started

Clerk requires Java 11 or newer and clojure installed.
🤹 Clerk Demo

When you're not yet familiar with Clerk, we recommend cloning and playing with the nextjournal/clerk-demo repo.
git clone git@github.com:nextjournal/clerk-demo.git
cd clerk-demo

Then open dev/user.clj from the project in your favorite editor and start a REPL into the project. For editor-specific instructions see:

    Emacs & Cider
    Calva
    Cursive
    Vim & Neovim

🔌 In an Existing Project

To use Clerk in your project, add the following dependency to your deps.edn:
{:deps {io.github.nextjournal/clerk {:mvn/version "0.17.1102"}}}

Require and start Clerk as part of your system start, e.g. in user.clj:
(require '[nextjournal.clerk :as clerk])

;; start Clerk's built-in webserver on the default port 7777, opening the browser when done
(clerk/serve! {:browse? true})

;; either call `clerk/show!` explicitly to show a given notebook, or use the File Watcher described below.
(clerk/show! "notebooks/rule_30.clj")

You can then access Clerk at http://localhost:7777.
⏱ File Watcher

You can load, evaluate, and present a file with the clerk/show! function, but in most cases it's easier to start a file watcher with something like:
(clerk/serve! {:watch-paths ["notebooks" "src"]})

... which will automatically reload and re-eval any clojure (clj) or markdown (md) files that change, displaying the most recently changed one in your browser.

To make this performant enough to feel good, Clerk caches the computations it performs while evaluating each file. Likewise, to make sure it doesn't send too much data to the browser at once, Clerk paginates data structures within an interactive viewer.
🔪 Editor Integration

A recommended alternative to the file watcher is setting up a hotkey in your editor to save & clerk/show! the active file.

Emacs

In Emacs, add the following to your config:
(defun clerk-show ()
  (interactive)
  (when-let
      ((filename
        (buffer-file-name)))
    (save-buffer)
    (cider-interactive-eval
     (concat "(nextjournal.clerk/show! \"" filename "\")"))))

(define-key clojure-mode-map (kbd "<M-return>") 'clerk-show)

IntelliJ/Cursive

In IntelliJ/Cursive, you can set up REPL commands via:

    going to Tools→REPL→Add New REPL Command, then
    add the following command: (show! "~file-path");
    make sure the command is executed in the nextjournal.clerk namespace;
    lastly assign a shortcut of your choice via Settings→Keymap

Neovim + Conjure

With neovim + conjure one can use the following vimscript function to save the file and show it with Clerk:
function! ClerkShow()
exe "w"
exe "ConjureEval (nextjournal.clerk/show! \"" . expand("%:p") . "\")"
endfunction

nmap <silent> <localleader>cs :execute ClerkShow()<CR>
🔍 Viewers

Clerk comes with a number of useful built-in viewers e.g. for Clojure data, html & hiccup, tables, plots &c.

When showing large data structures, Clerk's default viewers will paginate the results.
🧩 Clojure Data

The default set of viewers are able to render Clojure data.
(def clojure-data
  {:hello "world 👋"
   :tacos (map #(repeat % '🌮) (range 1 30))
   :zeta "The\npurpose\nof\nvisualization\nis\ninsight,\nnot\npictures."})
{:hello "
world 👋"
:tacos ((🌮) (🌮 🌮) (🌮 🌮 🌮) (🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 16 more elided) (20 more elided) 9 more elided) :zeta "
The↩︎purpose↩︎of↩︎visualization↩︎is↩︎insight,↩︎not↩︎pictures."}

Viewers can handle lazy infinite sequences, partially loading data by default with the ability to load more data on request.
(range)
(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 1000000+ more elided)
(def fib (lazy-cat [0 1] (map + fib (rest fib))))
(0 1 1 2 3 5 8 13 21 34 55 89 144 233 377 610 987 1597 2584 4181 73 more elided)

In addition, there's a number of built-in viewers that can be called explicity using functions.
🌐 Hiccup, HTML & SVG

The html viewer interprets hiccup when passed a vector.
(clerk/html [:div "As Clojurians we " [:em "really"] " enjoy hiccup"])
As Clojurians we really enjoy hiccup

Alternatively you can pass it an HTML string.
(clerk/html "Never <strong>forget</strong>.")
Never forget.

You can style elements, using Tailwind CSS.
(clerk/html [:button.bg-sky-500.hover:bg-sky-700.text-white.rounded-xl.px-2.py-1 "✨ Tailwind CSS"])

The html viewer is able to display SVG, taking either a hiccup vector or a SVG string.
(clerk/html [:svg {:width 500 :height 100}
             [:circle {:cx  25 :cy 50 :r 25 :fill "blue"}]
             [:circle {:cx 100 :cy 75 :r 25 :fill "red"}]])

You can also embed other viewers inside of hiccup.
(clerk/html [:div.flex.justify-center.space-x-6
             [:p "a table is next to me"]
             (clerk/table [[1 2] [3 4]])])

a table is next to me
1	2
3	4
🔢 Tables

Clerk provides a built-in data table viewer that supports the three most common tabular data shapes out of the box: a sequence of maps, where each map's keys are column names; a seq of seqs, which is just a grid of values with an optional header; a map of seqs, in which keys are column names and rows are the values for that column.
(clerk/table [[1 2]
              [3 4]]) ;; seq of seqs
1	2
3	4
(clerk/table (clerk/use-headers [["odd numbers" "even numbers"]
                                 [1 2]
                                 [3 4]])) ;; seq of seqs with header
odd numbers	even numbers
1	2
3	4
(clerk/table [{"odd numbers" 1 "even numbers" 2}
              {"odd numbers" 3 "even numbers" 4}]) ;; seq of maps
odd numbers	even numbers
1	2
3	4
(clerk/table {"odd numbers" [1 3]
              "even numbers" [2 4]}) ;; map of seqs
odd numbers	even numbers
1	2
3	4

Internally the table viewer will normalize all of the above to a map with :rows and an optional :head key, also giving you control over the column order.
(clerk/table {:head ["odd numbers" "even numbers"]
              :rows [[1 2] [3 4]]}) ;; map with `:rows` and optional `:head` keys
odd numbers	even numbers
1	2
3	4

To customize the number of rows in the table viewer, set ::clerk/page-size. Use a value of nil to show all rows.
(clerk/table {::clerk/page-size 7} (map (comp vector (partial str "Row #")) (range 1 31)))
Row #1
Row #2
Row #3
Row #4
Row #5
Row #6
Row #7
23 more elided

The built-in table viewer adds a number of child-viewers on its :add-viewers key. Those sub-viewers control the markup for the table and the display of strings (to turn off quoting inside table cells).
(:add-viewers v/table-viewer)
[{:name nextjournal.clerk.viewer/string-viewer :page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render.table/render-table-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/elision-viewer :render-fn nextjournal.clerk.render.table/render-table-elision :transform-fn #object[nextjournal.clerk.viewer$mark_presented 0x6e581630 "
nextjournal.clerk.viewer$mark_presented@6e581630"
]} {:name nextjournal.clerk.viewer/table-missing-viewer :pred #{:nextjournal/missing} :render-fn (fn [x] [:<>])} {:name nextjournal.clerk.viewer/table-markup-viewer :render-fn nextjournal.clerk.render.table/render-table-markup} {:name nextjournal.clerk.viewer/table-head-viewer :render-fn nextjournal.clerk.render.table/render-table-head} {:name nextjournal.clerk.viewer/table-body-viewer :render-fn nextjournal.clerk.render.table/render-table-body} {:name nextjournal.clerk.viewer/table-row-viewer :render-fn nextjournal.clerk.render.table/render-table-row}]

Modifying the :add-viewers key allows us to create a custom table viewer that shows missing values differently.
(def table-viewer-custom-missing-values
  (update v/table-viewer :add-viewers v/add-viewers [(assoc v/table-missing-viewer :render-fn '(fn [x] [:span.red "N/A"]))]))
{:add-viewers [{:name nextjournal.clerk.viewer/string-viewer :page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render.table/render-table-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/elision-viewer :render-fn nextjournal.clerk.render.table/render-table-elision :transform-fn #object[nextjournal.clerk.viewer$mark_presented 0x6e581630 "
nextjournal.clerk.viewer$mark_presented@6e581630"
]} {:name nextjournal.clerk.viewer/table-missing-viewer :pred #{:nextjournal/missing} :render-fn (fn [x] [:span.red "
N/A"])}
{:name nextjournal.clerk.viewer/table-markup-viewer :render-fn nextjournal.clerk.render.table/render-table-markup} {:name nextjournal.clerk.viewer/table-head-viewer :render-fn nextjournal.clerk.render.table/render-table-head} {:name nextjournal.clerk.viewer/table-body-viewer :render-fn nextjournal.clerk.render.table/render-table-body} {:name nextjournal.clerk.viewer/table-row-viewer :render-fn nextjournal.clerk.render.table/render-table-row}] :name nextjournal.clerk.viewer/table-viewer :page-size 20 :transform-fn #object[nextjournal.clerk.viewer$fn__15952 0x6043dfeb "
nextjournal.clerk.viewer$fn__15952@6043dfeb"
]}
^{::clerk/viewer table-viewer-custom-missing-values}
{:A [1 2 3] :B [1 3] :C [1 2]}
:A	:B	:C
1	1	1
2	3	2
3	N/A	N/A
🧮 TeX

As we've already seen, all comment blocks can contain TeX (we use KaTeX under the covers). In addition, you can call the TeX viewer programmatically. Here, for example, are Maxwell's equations in differential form:
(clerk/tex "
\\begin{alignedat}{2}
  \\nabla\\cdot\\vec{E} = \\frac{\\rho}{\\varepsilon_0} & \\qquad \\text{Gauss' Law} \\\\
  \\nabla\\cdot\\vec{B} = 0 & \\qquad \\text{Gauss' Law ($\\vec{B}$ Fields)} \\\\
  \\nabla\\times\\vec{E} = -\\frac{\\partial \\vec{B}}{\\partial t} & \\qquad \\text{Faraday's Law} \\\\
  \\nabla\\times\\vec{B} = \\mu_0\\vec{J}+\\mu_0\\varepsilon_0\\frac{\\partial\\vec{E}}{\\partial t} & \\qquad \\text{Ampere's Law}
\\end{alignedat}
")
∇⋅E⃗=ρε0Gauss’ Law∇⋅B⃗=0Gauss’ Law (B⃗ Fields)∇×E⃗=−∂B⃗∂tFaraday’s Law∇×B⃗=μ0J⃗+μ0ε0∂E⃗∂tAmpere’s Law
∇⋅E
=ε0​ρ​∇⋅B
=0∇×E
=−∂t∂B
​∇×B
=μ0​J
+μ0​ε0​∂t∂E
​​Gauss’ LawGauss’ Law (B
 Fields)Faraday’s LawAmpere’s Law​
📊 Plotly

Clerk also has built-in support for Plotly's low-ceremony plotting. See Plotly's JavaScript docs for more examples and options.
(clerk/plotly {:data [{:z [[1 2 3] [3 2 1]] :type "surface"}]
               :layout {:margin {:l 20 :r 0 :b 20 :t 20}
                        :paper_bgcolor "transparent"
                        :plot_bgcolor "transparent"}
               :config {:displayModeBar false
                        :displayLogo false}})
🗺 Vega Lite

But Clerk also has Vega Lite for those who prefer that grammar.
(clerk/vl {:width 650 :height 400 :data {:url "https://vega.github.io/vega-datasets/data/us-10m.json"
                                         :format {:type "topojson" :feature "counties"}}
           :transform [{:lookup "id" :from {:data {:url "https://vega.github.io/vega-datasets/data/unemployment.tsv"}
                                            :key "id" :fields ["rate"]}}]
           :projection {:type "albersUsa"} :mark "geoshape" :encoding {:color {:field "rate" :type "quantitative"}}
           :background "transparent"
           :embed/opts {:actions false}})

You can provide a map of embed options to the vega viewer via the :embed/opts key.

Clerk handles conversion from EDN to JSON for you. The official Vega-Lite examples are in JSON, but a Clojure/EDN version is available: Carsten Behring's Vega gallery in EDN.
🎼 Code

By default the code viewer uses clojure-mode for syntax highlighting.
(clerk/code (macroexpand '(when test
                            expression-1
                            expression-2)))
(if test (do expression-1 expression-2))
(clerk/code '(ns foo "A great ns" (:require [clojure.string :as str])))
(ns foo "A great ns" (:require [clojure.string :as str]))
(clerk/code "(defn my-fn\n  \"This is a Doc String\"\n  [args]\n  42)")
(defn my-fn
  "This is a Doc String"
  [args]
  42)

You can specify the language for syntax highlighting via ::clerk/opts.
(clerk/code {::clerk/opts {:language "python"}} "
class Foo(object):
    def __init__(self):
        pass
    def do_this(self):
        return 1")

class Foo(object):
    def __init__(self):
        pass
    def do_this(self):
        return 1

Or use a code fence with a language in a markdown.
(clerk/md "```c++
#include <iostream>
int main() {
    std::cout << \" Hello, world! \" << std::endl
    return 0
}
```")
#include <iostream>
int main() {
    std::cout << " Hello, world! " << std::endl
    return 0
}
🏞 Images

Clerk offers the clerk/image viewer to create a buffered image from a string or anything javax.imageio.ImageIO/read can take (URL, File or InputStream).

For example, we can fetch a photo of De zaaier, Vincent van Gogh's famous painting of a farmer sowing a field from Wiki Commons like this:
(clerk/image "https://upload.wikimedia.org/wikipedia/commons/thumb/3/31/The_Sower.jpg/1510px-The_Sower.jpg")

We've put some effort into making the default image rendering pleasing. The viewer uses the dimensions and aspect ratio of each image to guess the best way to display it in classic DWIM fashion. For example, an image larger than 900px wide with an aspect ratio larger then two will be displayed full width:
(clerk/image "https://images.unsplash.com/photo-1532879311112-62b7188d28ce?ixlib=rb-1.2.1&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8")

On the other hand, smaller images are centered and shown using their intrinsic dimensions:
(clerk/image "https://nextjournal.com/data/QmSJ6eu6kUFeWrqXyYaiWRgJxAVQt2ivaoNWc1dtTEADCf?filename=thermo.png&content-type=image/png")

You can use clerk/image together with clerk/caption which will render a simple caption under the image:
(clerk/caption
 "Implements of the Paper Printing Industry"
 (clerk/image "https://nextjournal.com/data/QmX99isUndwqBz7nj8fdG7UoDakNDSH1TZcvY2Y6NUTe6o?filename=image.gif&content-type=image/gif"))

Implements of the Paper Printing Industry

Captions aren't limited to images and work together with any arbitrary content that you provide, e.g. a table:
show code
Solfège	French IPA	English IPA	Meaning
Do	/do/	/doʊ/	no
Re	/ʁɛ/	/ɹeɪ/	and, also
Mi	/mi/	/miː/	or
Fa	/fa/	/fɑː/	at, to
Sol	/sɔl/	/soʊl/	but, if
La	/la/	/lɑː/	the, then
Si	/si/	/siː/	yes

Modern Symmetrical Unary(7) in Solresol
📒 Markdown

The same Markdown support Clerk uses for comment blocks is also available programmatically:
(clerk/md (clojure.string/join "\n" (map #(str "* Item " (inc %)) (range 3))))

    Item 1
    Item 2
    Item 3

For a more advanced example of ingesting markdown files and transforming the content to HTML using Hiccup, see notebooks/markdown.md in the clerk-demo repo.
🔠 Grid Layouts

Layouts can be composed via rows and cols.

Passing :width, :height or any other style attributes to ::clerk/opts will assign them on the row or col that contains your items. You can use this to size your containers accordingly.
(clerk/row image-1 image-2 image-3)
(clerk/col {::clerk/opts {:width 150}} image-1 image-2 image-3)
{:nextjournal.clerk/opts {:width 150}}

Laying out stuff is not limited to images. You can use it to lay out any Clerk viewer. E.g. combine it with HTML viewers to render nice captions:
(defn caption [text]
  (clerk/html [:figcaption.text-center.mt-1 text]))
#object[nextjournal.clerk.book$caption 0x321f25ed "
nextjournal.clerk.book$caption@321f25ed"
]
(clerk/row
 (clerk/col image-1 (caption "Figure 1: Decorative A"))
 (clerk/col image-2 (caption "Figure 2: Decorative B"))
 (clerk/col image-3 (caption "Figure 3: Decorative C")))
Figure 1: Decorative A
Figure 2: Decorative B
Figure 3: Decorative C

Note: the caption example is exactly how clerk/caption is implemented in Clerk.

Alternative notations

By default, row and col operate on & rest so you can pass any number of items to the functions. But the viewers are smart enough to accept any sequential list of items.
(v/row [image-1 image-2 image-3])
🍱 Composing Viewers

Viewers compose, so, for example, you can lay out multiple independent Vega charts using Clerk’s grid viewers:
show code
#'nextjournal.clerk.book/stock-chart
(clerk/col
 (clerk/row (stock-chart "AAPL")
            (stock-chart "AMZN")
            (stock-chart "GOOG")
            (stock-chart "IBM")
            (stock-chart "MSFT"))
 combined-stocks-chart)

Viewers can also be embedded in Hiccup. The following example shows how this is used to provide a custom callout for a clerk/image.
(clerk/html
 [:div.relative
  (clerk/image "https://images.unsplash.com/photo-1608993659399-6508f918dfde?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8&auto=format&fit=crop&w=2070&q=80")
  [:div.absolute
   {:class "left-[25%] top-[21%]"}
   [:div.border-4.border-emerald-400.rounded-full.shadow
    {:class "w-8 h-8"}]
   [:div.border-t-4.border-emerald-400.absolute
    {:class "w-[80px] rotate-[30deg] left-4 translate-x-[10px] translate-y-[10px]"}]
   [:div.border-4.border-emerald-400.absolute.text-white.font-sans.p-3.rounded-md
    {:class "bg-black bg-opacity-60 text-[13px] w-[280px] top-[66px]"}
    "Cat's paws are adapted to climbing and jumping, walking and running, and have protractible claws for self-defense and hunting."]]])
Cat's paws are adapted to climbing and jumping, walking and running, and have protractible claws for self-defense and hunting.
🤹🏻 Applying Viewers

Metadata Notation

In the examples above, we've used convenience helper functions like clerk/html or clerk/plotly to wrap values in a viewer. If you call this on the REPL, you'll notice a given value gets wrapped in a map under the :nextjournal/value key with the viewer being in the :nextjournal/viewer key.

You can also select a viewer using Clojure metadata in order to avoid Clerk interfering with the value.
^{::clerk/viewer clerk/table}
(def my-dataset
  [{:temperature 41.0 :date (java.time.LocalDate/parse "2022-08-01")}
   {:temperature 39.0 :date (java.time.LocalDate/parse "2022-08-01")}
   {:temperature 34.0 :date (java.time.LocalDate/parse "2022-08-01")}
   {:temperature 29.0 :date (java.time.LocalDate/parse "2022-08-01")}])
:temperature	:date
41	#object[java.time.LocalDate 0x2bc0bbd2 "
2022-08-01"
]
39	#object[java.time.LocalDate 0x32d93558 "
2022-08-01"
]
34	#object[java.time.LocalDate 0x6e9e31a0 "
2022-08-01"
]
29	#object[java.time.LocalDate 0x2231761a "
2022-08-01"
]

As you can see above, the table viewer is being applied to the value of the my-dataset var, not the var itself. If you want your viewer to access the raw var, you can opt out of this with a truthy :var-from-def? key on the viewer.
^{::clerk/viewer (assoc v/fallback-viewer :var-from-def? true)}
(def raw-var :baz)
{:nextjournal.clerk/var-from-def (var nextjournal.clerk.book/raw-var) :nextjournal.clerk/var-snapshot :baz}
👁 Writing Viewers

Let's explore how Clerk viewers work and how you create your own to gain better insight into your problem at hand.
v/default-viewers
[{:name nextjournal.clerk.viewer/header-viewer :transform-fn #object[clojure.core$comp$fn__5825 0xc8157a5 "
clojure.core$comp$fn__5825@c8157a5"
]} {:name nextjournal.clerk.viewer/toc-viewer :render-fn nextjournal.clerk.render.navbar/render-items :transform-fn #object[nextjournal.clerk.viewer$transform_toc 0x7789d098 "
nextjournal.clerk.viewer$transform_toc@7789d098"
]} {:name nextjournal.clerk.viewer/char-viewer :pred #object[clojure.core$char_QMARK___5425 0x6885fcf8 "
clojure.core$char_QMARK___5425@6885fcf8"
] :render-fn (fn [c] [:span.cmt-string.inspected-value "
\"
c])} {:closing-paren "
""
:name nextjournal.clerk.viewer/string-viewer :opening-paren "
""
:page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-quoted-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render/render-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/number-hex-viewer :render-fn (fn [num] (nextjournal.clerk.render/render-number (str "
0x"
(.toString (js/Number. num) 16))))} {:name nextjournal.clerk.viewer/symbol-viewer :pred #object[clojure.core$symbol_QMARK_ 0x3c291aad "
clojure.core$symbol_QMARK_@3c291aad"
] :render-fn (fn [x] [:span.cmt-keyword.inspected-value (str x)])} {:name nextjournal.clerk.viewer/keyword-viewer :pred #object[clojure.core$keyword_QMARK_ 0x412dd654 "
clojure.core$keyword_QMARK_@412dd654"
] :render-fn (fn [x] [:span.cmt-atom.inspected-value (str x)])} {:name nextjournal.clerk.viewer/nil-viewer :pred #object[clojure.core$nil_QMARK_ 0x47179dab "
clojure.core$nil_QMARK_@47179dab"
] :render-fn (fn [_] [:span.cmt-default.inspected-value "
nil"])}
{:name nextjournal.clerk.viewer/boolean-viewer :pred #object[clojure.core$boolean_QMARK_ 0x51c929ae "
clojure.core$boolean_QMARK_@51c929ae"
] :render-fn (fn [x] [:span.cmt-bool.inspected-value (str x)])} {:name nextjournal.clerk.viewer/map-entry-viewer :page-size 2 :pred #object[clojure.core$map_entry_QMARK_ 0x7e2388c1 "
clojure.core$map_entry_QMARK_@7e2388c1"
] :render-fn (fn [xs opts] (into [:<>] (comp (nextjournal.clerk.render/inspect-children opts) (interpose "
 "))
xs))} {:name nextjournal.clerk.viewer/var-from-def-viewer :pred #object[nextjournal.clerk.viewer$var_from_def_QMARK_ 0x3906d9c1 "
nextjournal.clerk.viewer$var_from_def_QMARK_@3906d9c1"
] :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x16b637c3 "
nextjournal.clerk.viewer$update_val$fn__15695@16b637c3"
]} {:name nextjournal.clerk.viewer/read+inspect-viewer 1 more elided} {6 more elided} {6 more elided} {6 more elided} {5 more elided} {3 more elided} {3 more elided} {6 more elided} 23 more elided]

These are the default viewers that come with Clerk.
(into #{} (map type) v/default-viewers)
#{clojure.lang.PersistentArrayMap}

Each viewer is a simple Clojure map.
(assoc (frequencies (mapcat keys v/default-viewers)) :total (count v/default-viewers))
{:add-viewers 2 :closing-paren 5 :name 43 :opening-paren 5 :page-size 7 :pred 20 :render-fn 35 :total 43 :transform-fn 27 :var-from-def? 1}

We have a total of 43 viewers in the defaults. Let's start with a simple example and explain the different extensions points in the viewer api.
🎪 Presentation

Clerk's rendering happens in the browser. On the Clojure-side, a given document is presented. Presenting takes a value and transforms it such that Clerk can send it to the browser where it will be rendered.

Let's start with one of the simplest examples. You can see that present takes our value 1 and transforms it into a map, with 1 under a :nextjournal/value key and the number viewer assigned under the :nextjournal/viewer key. We call this map a wrapped-value.
^{::clerk/viewer v/inspect-wrapped-values ::clerk/auto-expand-results? true}
(clerk/present 1)
{:path []
 :nextjournal/value 1
 :nextjournal/viewer {:hash "
5dr3R1ZvHCoRHpxsQq4D4ARv9BQC7o"

 :name nextjournal.clerk.viewer/number-viewer
 :render-fn {:form nextjournal.clerk.render/render-number
 :render-evaluator :sci}}}

This data structure is sent over Clerk's websocket to the browser, where it will be displayed using the :render-fn found in the :nextjournal/viewer key.

Now onto something slightly more complex, #{1 2 3}.
^{::clerk/viewer v/inspect-wrapped-values ::clerk/auto-expand-results? true}
(clerk/present #{1 2 3})
{:path []
 :nextjournal/value [{:path [0]
 :nextjournal/value 1
 :nextjournal/viewer {:hash "
5dr3R1ZvHCoRHpxsQq4D4ARv9BQC7o"

 :name nextjournal.clerk.viewer/number-viewer
 :render-fn {:form nextjournal.clerk.render/render-number
 :render-evaluator :sci}}}
 {:path [1]
 :nextjournal/value 2
 :nextjournal/viewer {:hash "
5dr3R1ZvHCoRHpxsQq4D4ARv9BQC7o"

 :name nextjournal.clerk.viewer/number-viewer
 :render-fn {:form nextjournal.clerk.render/render-number
 :render-evaluator :sci}}}
 {:path [2]
 :nextjournal/value 3
 :nextjournal/viewer {:hash "
5dr3R1ZvHCoRHpxsQq4D4ARv9BQC7o"

 :name nextjournal.clerk.viewer/number-viewer
 :render-fn {:form nextjournal.clerk.render/render-number
 :render-evaluator :sci}}}]
 :nextjournal/viewer {:closing-paren ("
}")

 :hash "
5drg8hfjVALoJFYnysrUYCoTb1MquJ"

 :name nextjournal.clerk.viewer/set-viewer
 :opening-paren "
#{"

 :page-size 20
 :render-fn {:form nextjournal.clerk.render/render-coll :render-evaluator :sci}}}

Here, we're giving it a set with 1, 2, 3 in it. In its generalized form, present is a function that does a depth-first traversal of a given tree, starting at the root node. It will select a viewer for this root node, and unless told otherwise, descend further down the tree to present its child nodes.

Compare this with the simple 1 example above! You should recognize the leaf values. Also note that the container is no longer a set, but it has been transformed into a vector. This transformation exists to support pagination of long unordered sequences like maps and sets and so we can efficiently access a value inside this tree using get-in.

You might ask yourself why we don't just send the unmodified value to the browser. For one, we could easily overload the browser with too much data. Secondly we will look at examples of being able to select viewers based on Clojure and Java types, which cannot be serialized and sent to the browser.
⚙️ Transform

When writing your own viewer, the first extension point you should reach for is :transform-fn.
(v/with-viewer {:transform-fn v/inspect-wrapped-values}
  "Exploring the viewer api")
{:!budget #object[clojure.lang.Atom 0xfb0f28c {:status :ready :val 199}] :file "
book.clj"
:form (v/with-viewer {:transform-fn v/inspect-wrapped-values} "
Exploring the viewer api")
:freezable? true :id nextjournal.clerk.book/anon-expr-5dtabEGb3fNTM1UjSgqT8AvepcVHzw :loc {:column 1 :end-column 30 :end-line 559 :line 558} :path [1] :present-elision-fn #object[clojure.core$partial$fn__5857 0x7a454789 "
clojure.core$partial$fn__5857@7a454789"
] :settings {:nextjournal.clerk/visibility {:code :show :result :show}} :store!-wrapped-value #object[nextjournal.clerk.viewer$present$fn__16197 0x45de4601 "
nextjournal.clerk.viewer$present$fn__16197@45de4601"
] 8 more elided}

As you can see the argument to the :transform-fn isn't just the string we're passing it, but a map with the original value under a :nextjournal/value key. We call this map a wrapped-value. We will look at what this enables in a bit. But let's look at one of the simplest examples first.

A first simple example
(def greet-viewer
  {:transform-fn (clerk/update-val #(clerk/html [:strong "Hello, " % " 👋"]))})
{:transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x248b77bf "
nextjournal.clerk.viewer$update_val$fn__15695@248b77bf"
]}

For this simple greet-viewer we're only doing a simple value transformation. For this, clerk/update-val is a small helper function which takes a function f and returns a function to update only the value inside a wrapped-value, a shorthand for #(update % :nextjournal/val f)
(v/with-viewer greet-viewer
  "James Clerk Maxwell")
Hello, James Clerk Maxwell 👋

The :transform-fn runs on the JVM, which means you can explore what it does at your REPL by calling clerk/present on such a value.
^{::clerk/viewer v/inspect-wrapped-values}
(clerk/present (v/with-viewer greet-viewer
                 "James Clerk Maxwell"))
{:path [] :nextjournal/value [:strong "
Hello, "
"
James Clerk Maxwell"
"
 👋"]
:nextjournal/viewer {:hash "
5drpr3yzJ1CcHNbRHnK2sVyn7YUmXB"
:name nextjournal.clerk.viewer/html-viewer :render-fn {:form nextjournal.clerk.render/render-html :render-evaluator :sci}}}

Passing modified viewers down the tree
v/table-viewer
{:add-viewers [{:name nextjournal.clerk.viewer/string-viewer :page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render.table/render-table-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/elision-viewer :render-fn nextjournal.clerk.render.table/render-table-elision :transform-fn #object[nextjournal.clerk.viewer$mark_presented 0x6e581630 "
nextjournal.clerk.viewer$mark_presented@6e581630"
]} {:name nextjournal.clerk.viewer/table-missing-viewer :pred #{:nextjournal/missing} :render-fn (fn [x] [:<>])} {:name nextjournal.clerk.viewer/table-markup-viewer :render-fn nextjournal.clerk.render.table/render-table-markup} {:name nextjournal.clerk.viewer/table-head-viewer :render-fn nextjournal.clerk.render.table/render-table-head} {:name nextjournal.clerk.viewer/table-body-viewer :render-fn nextjournal.clerk.render.table/render-table-body} {:name nextjournal.clerk.viewer/table-row-viewer :render-fn nextjournal.clerk.render.table/render-table-row}] :name nextjournal.clerk.viewer/table-viewer :page-size 20 :transform-fn #object[nextjournal.clerk.viewer$fn__15952 0x6043dfeb "
nextjournal.clerk.viewer$fn__15952@6043dfeb"
]}
(def custom-table-viewer
  (update v/table-viewer :add-viewers v/add-viewers [(assoc v/table-head-viewer :transform-fn (v/update-val (partial map (comp (partial str "Column: ") str/capitalize name))))
                                                     (assoc v/table-missing-viewer :render-fn '(fn [x] [:span.red "N/A"]))]))
{:add-viewers [{:name nextjournal.clerk.viewer/string-viewer :page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render.table/render-table-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/elision-viewer :render-fn nextjournal.clerk.render.table/render-table-elision :transform-fn #object[nextjournal.clerk.viewer$mark_presented 0x6e581630 "
nextjournal.clerk.viewer$mark_presented@6e581630"
]} {:name nextjournal.clerk.viewer/table-missing-viewer :pred #{:nextjournal/missing} :render-fn (fn [x] [:span.red "
N/A"])}
{:name nextjournal.clerk.viewer/table-markup-viewer :render-fn nextjournal.clerk.render.table/render-table-markup} {:name nextjournal.clerk.viewer/table-head-viewer :render-fn nextjournal.clerk.render.table/render-table-head :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x1921dbdb "
nextjournal.clerk.viewer$update_val$fn__15695@1921dbdb"
]} {:name nextjournal.clerk.viewer/table-body-viewer :render-fn nextjournal.clerk.render.table/render-table-body} {:name nextjournal.clerk.viewer/table-row-viewer :render-fn nextjournal.clerk.render.table/render-table-row}] :name nextjournal.clerk.viewer/table-viewer :page-size 20 :transform-fn #object[nextjournal.clerk.viewer$fn__15952 0x6043dfeb "
nextjournal.clerk.viewer$fn__15952@6043dfeb"
]}
(clerk/with-viewer custom-table-viewer
  {:col/a [1 2 3 4] :col/b [1 2 3] :col/c [1 2 3]})
Column: A	Column: B	Column: C
1	1	1
2	2	2
3	3	3
4	N/A	N/A
(clerk/with-viewer custom-table-viewer
  {:col/a [1 2 3 4] :col/b [1 2 3] :col/c [1 2 3]})
Column: A	Column: B	Column: C
1	1	1
2	2	2
3	3	3
4	N/A	N/A
🐢 Recursion

But this presentation and hence tranformation of nodes further down the tree isn't always what you want. For example, the plotly or vl viewers want to receive the child value unaltered in order to use it as a spec.

To stop Clerk's presentation from descending into child nodes, use clerk/mark-presented as a :transform-fn. Compare the result below in which [1 2 3] appears unaltered with what you see above.
^{::clerk/viewer v/inspect-wrapped-values}
(clerk/present (clerk/with-viewer {:transform-fn clerk/mark-presented
                                   :render-fn '(fn [x] [:pre (pr-str x)])}
                 [1 2 3]))
{:path [] :nextjournal/value [1 2 3] :nextjournal/viewer {:hash "
5dt5b4pxRDZuohXb9zeaq6xmBbh78u"
:render-fn {:form (fn [x] [:pre (pr-str x)]) :render-evaluator :sci}}}

Clerk's presentation will also transform maps into sequences in order to paginate large maps. When you're dealing with a map that you know is bounded and would like to preserve its keys, there's clerk/mark-preserve-keys. This will still transform (and paginate) the values of the map, but leave the keys unaltered.
^{::clerk/viewer v/inspect-wrapped-values ::clerk/auto-expand-results? true}
(clerk/present (clerk/with-viewer {:transform-fn clerk/mark-preserve-keys}
                 {:hello 42}))
{:path []
 :nextjournal/value {:hello {:path [:hello]
 :nextjournal/value 42
 :nextjournal/viewer {:hash "
5dr3R1ZvHCoRHpxsQq4D4ARv9BQC7o"

 :name nextjournal.clerk.viewer/number-viewer
 :render-fn {:form nextjournal.clerk.render/render-number
 :render-evaluator :sci}}}}
 :nextjournal/viewer {:closing-paren ("
}")

 :hash "
5drc3ac5ux7kS4h2vozdxwsrKDqSmn"

 :name nextjournal.clerk.viewer/map-viewer
 :opening-paren "
{"

 :page-size 10
 :render-fn {:form nextjournal.clerk.render/render-map :render-evaluator :sci}}}
🔬 Render

As we've just seen, you can also do a lot with :transform-fn and using clerk/html on the JVM. When you want to run code in the browser where Clerk's viewers are rendered, reach for :render-fn. As an example, we'll write a multiviewer for a emmy literal expression that will compute two alternative representations and let the user switch between them in the browser.

We start with a simple function that takes such an expression and turns it into a map with two representations, one TeX and the original form.
(defn transform-literal [expr]
  {:TeX (-> expr emmy/->TeX clerk/tex)
   :original (clerk/code (with-out-str (emmy/print-expression (emmy/freeze expr))))})
#object[nextjournal.clerk.book$transform_literal 0x4908be79 "
nextjournal.clerk.book$transform_literal@4908be79"
]

Our literal-viewer calls this transform-literal function and also calls clerk/mark-preserve-keys. This tells Clerk to leave the keys of the map as-is.

In our :render-fn, which is called in the browser, we will receive this map. Note that this is a quoted form, not a function. Clerk will send this form to the browser for evaluation. There it will create a reagent/atom that holds the selection state. Lastly, nextjournal.clerk.render/inspect-presented is a component that takes a wrapped-value that ran through clerk/present and show it.
(def literal-viewer
  {:pred emmy.expression/literal?
   :transform-fn (comp clerk/mark-preserve-keys
                       (clerk/update-val transform-literal))
   :render-fn '(fn [label->val]
                 (reagent.core/with-let [!selected-label (reagent.core/atom (ffirst label->val))]
                   [:<> (into
                         [:div.flex.items-center.font-sans.text-xs.mb-3
                          [:span.text-slate-500.mr-2 "View-as:"]]
                         (map (fn [label]
                                [:button.px-3.py-1.font-medium.hover:bg-indigo-50.rounded-full.hover:text-indigo-600.transition
                                 {:class (if (= @!selected-label label) "bg-indigo-100 text-indigo-600" "text-slate-500")
                                  :on-click #(reset! !selected-label label)}
                                 label]))
                         (keys label->val))
                    [nextjournal.clerk.render/inspect-presented (get label->val @!selected-label)]]))})
{:pred #object[emmy.expression$literal_QMARK_ 0x7e510717 "
emmy.expression$literal_QMARK_@7e510717"
] :render-fn (fn [label->val] (reagent.core/with-let [!selected-label (reagent.core/atom (ffirst label->val))] [:<> (into [:div.flex.items-center.font-sans.text-xs.mb-3 [:span.text-slate-500.mr-2 "
View-as:"]]
(map (fn [label] [:button.px-3.py-1.font-medium.hover:bg-indigo-50.rounded-full.hover:text-indigo-600.transition {:class (if (= (clojure.core/deref !selected-label) label) "
bg-indigo-100 text-indigo-600"
"
text-slate-500")
:on-click (fn* [] (reset! !selected-label label))} label])) (keys label->val)) [nextjournal.clerk.render/inspect-presented (get label->val (clojure.core/deref !selected-label))]])) :transform-fn #object[clojure.core$comp$fn__5825 0x198b1ed "
clojure.core$comp$fn__5825@198b1ed"
]}

Now let's see if this works. Try switching to the original representation!
^{::clerk/viewer literal-viewer}
(emmy/+ (emmy/square (emmy/sin 'x))
        (emmy/square (emmy/cos 'x)))
View-as:
sin⁡2(x)+cos⁡2(x)
sin2(x)+cos2(x)
📚 Require CLJS

Writing :render-fns inline as quoted forms is fine when they're small and independent. For more complex needs, Clerk supports loading ClojureScript files from the classpath.

To opt into this, use a fully qualified symbol as the :render-fn and set :require-cljs set to true. This way you tell Clerk to load this ClojureScript file (along with it's deps) into Clerk's SCI environment in the browser to make it useable there.
(def literal-viewer-require-cljs
  (assoc literal-viewer
         :require-cljs true
         :render-fn 'nextjournal.clerk.emmy/render-literal))
{:pred #object[emmy.expression$literal_QMARK_ 0x7e510717 "
emmy.expression$literal_QMARK_@7e510717"
] :render-fn nextjournal.clerk.emmy/render-literal :require-cljs true :transform-fn #object[clojure.core$comp$fn__5825 0x198b1ed "
clojure.core$comp$fn__5825@198b1ed"
]}

Writing a render function in regular .cljs file often works better with IDE-tooling like linters, REPLs and makes reusing existing ClojureScript code easier.
^{::clerk/viewer literal-viewer-require-cljs}
(emmy/+ (emmy/square (emmy/sin 'x))
        (emmy/square (emmy/cos 'x)))
View as:
sin⁡2(x)+cos⁡2(x)
sin2(x)+cos2(x)
🥇 Selection

Without a viewer specified, Clerk will go through the sequence of viewers and apply the :pred function in the viewer to find a matching one. Use v/viewer-for to select a viewer for a given value.
(def char?-viewer
  (v/viewer-for v/default-viewers \A))
{:name nextjournal.clerk.viewer/char-viewer :pred #object[clojure.core$char_QMARK___5425 0x6885fcf8 "
clojure.core$char_QMARK___5425@6885fcf8"
] :render-fn (fn [c] [:span.cmt-string.inspected-value "
\"
c])}

If we select a specific viewer (here the v/html-viewer using clerk/html) this is the viewer we will get.
(def html-viewer
  (v/viewer-for v/default-viewers (clerk/html [:h1 "foo"])))
{:name nextjournal.clerk.viewer/html-viewer :render-fn nextjournal.clerk.render/render-html :transform-fn #object[clojure.core$comp$fn__5825 0x37288e3 "
clojure.core$comp$fn__5825@37288e3"
]}

Instead of specifying a viewer for every value, we can also modify the viewers per namespace. Here, we add the literal-viewer from above to the whole namespace.
^{::clerk/visibility {:result :hide}}
(clerk/add-viewers! [literal-viewer])

As you can see we now get this viewer automatically, without needing to explicitly select it.
(emmy/+ (emmy/square (emmy/sin 'x))
        (emmy/square (emmy/cos 'x)))
View-as:
sin⁡2(x)+cos⁡2(x)
sin2(x)+cos2(x)
🔓 Elisions
(def string?-viewer
  (v/viewer-for v/default-viewers "Denn wir sind wie Baumstämme im Schnee."))
{:closing-paren "
""
:name nextjournal.clerk.viewer/string-viewer :opening-paren "
""
:page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-quoted-string}

Notice that for the string? viewer above, there's a :page-size of 80. This is the case for all collection viewers in Clerk and controls how many elements are displayed. So using the default string?-viewer above, we're showing the first 80 characters.
(def long-string
  (str/join (into [] cat (repeat 10 "Denn wir sind wie Baumstämme im Schnee.\n"))))
"
Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.320 more elided"

If we change the viewer and set a different :n in :page-size, we only see 10 characters.
(v/with-viewer (assoc string?-viewer :page-size 10)
  long-string)
"
Denn wir s390 more elided"

Or, we can turn off eliding, by dissoc'ing :page-size alltogether.
(v/with-viewer (dissoc string?-viewer :page-size)
  long-string)
"
Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee.↩︎Denn wir sind wie Baumstämme im Schnee."

The operations above were changes to a single viewer. But we also have a function update-viewers to update a given viewers by applying a select-fn->update-fn map. Here, the predicate is the keyword :page-size and our update function is called for every viewer with :page-size and is dissoc'ing them.
(def without-pagination
  {:page-size #(dissoc % :page-size)})
{:page-size #object[nextjournal.clerk.book$fn__52269 0x268c0439 "
nextjournal.clerk.book$fn__52269@268c0439"
]}

Here's the updated-viewers:
(def viewers-without-lazy-loading
  (v/update-viewers v/default-viewers without-pagination))
[{:name nextjournal.clerk.viewer/header-viewer :transform-fn #object[clojure.core$comp$fn__5825 0xc8157a5 "
clojure.core$comp$fn__5825@c8157a5"
]} {:name nextjournal.clerk.viewer/toc-viewer :render-fn nextjournal.clerk.render.navbar/render-items :transform-fn #object[nextjournal.clerk.viewer$transform_toc 0x7789d098 "
nextjournal.clerk.viewer$transform_toc@7789d098"
]} {:name nextjournal.clerk.viewer/char-viewer :pred #object[clojure.core$char_QMARK___5425 0x6885fcf8 "
clojure.core$char_QMARK___5425@6885fcf8"
] :render-fn (fn [c] [:span.cmt-string.inspected-value "
\"
c])} {:closing-paren "
""
:name nextjournal.clerk.viewer/string-viewer :opening-paren "
""
:pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-quoted-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render/render-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/number-hex-viewer :render-fn (fn [num] (nextjournal.clerk.render/render-number (str "
0x"
(.toString (js/Number. num) 16))))} {:name nextjournal.clerk.viewer/symbol-viewer :pred #object[clojure.core$symbol_QMARK_ 0x3c291aad "
clojure.core$symbol_QMARK_@3c291aad"
] :render-fn (fn [x] [:span.cmt-keyword.inspected-value (str x)])} {:name nextjournal.clerk.viewer/keyword-viewer :pred #object[clojure.core$keyword_QMARK_ 0x412dd654 "
clojure.core$keyword_QMARK_@412dd654"
] :render-fn (fn [x] [:span.cmt-atom.inspected-value (str x)])} {:name nextjournal.clerk.viewer/nil-viewer :pred #object[clojure.core$nil_QMARK_ 0x47179dab "
clojure.core$nil_QMARK_@47179dab"
] :render-fn (fn [_] [:span.cmt-default.inspected-value "
nil"])}
{:name nextjournal.clerk.viewer/boolean-viewer :pred #object[clojure.core$boolean_QMARK_ 0x51c929ae "
clojure.core$boolean_QMARK_@51c929ae"
] :render-fn (fn [x] [:span.cmt-bool.inspected-value (str x)])} {:name nextjournal.clerk.viewer/map-entry-viewer :pred #object[clojure.core$map_entry_QMARK_ 0x7e2388c1 "
clojure.core$map_entry_QMARK_@7e2388c1"
] :render-fn (fn [xs opts] (into [:<>] (comp (nextjournal.clerk.render/inspect-children opts) (interpose "
 "))
xs))} {:name nextjournal.clerk.viewer/var-from-def-viewer :pred #object[nextjournal.clerk.viewer$var_from_def_QMARK_ 0x3906d9c1 "
nextjournal.clerk.viewer$var_from_def_QMARK_@3906d9c1"
] :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x16b637c3 "
nextjournal.clerk.viewer$update_val$fn__15695@16b637c3"
]} {:name nextjournal.clerk.viewer/read+inspect-viewer :render-fn (fn 2 more elided)} {5 more elided} {5 more elided} {5 more elided} {5 more elided} {3 more elided} {3 more elided} {5 more elided} 23 more elided]

Now let's confirm these modified viewers don't have :page-size on them anymore.
(filter :page-size viewers-without-lazy-loading)
()

And compare it with the defaults:
(filter :page-size v/default-viewers)
({:closing-paren "
""
:name nextjournal.clerk.viewer/string-viewer :opening-paren "
""
:page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-quoted-string} {:name nextjournal.clerk.viewer/map-entry-viewer :page-size 2 :pred #object[clojure.core$map_entry_QMARK_ 0x7e2388c1 "
clojure.core$map_entry_QMARK_@7e2388c1"
] :render-fn (fn [xs opts] (into [:<>] (comp (nextjournal.clerk.render/inspect-children opts) (interpose "
 "))
xs))} {:closing-paren "
]"
:name nextjournal.clerk.viewer/vector-viewer :opening-paren "
["
:page-size 20 :pred #object[clojure.core$vector_QMARK___5431 0x2e31e04b "
clojure.core$vector_QMARK___5431@2e31e04b"
] :render-fn nextjournal.clerk.render/render-coll} {:closing-paren "
}"
:name nextjournal.clerk.viewer/set-viewer :opening-paren "
#{"
:page-size 20 :pred #object[clojure.core$set_QMARK_ 0x14c6ccfc "
clojure.core$set_QMARK_@14c6ccfc"
] :render-fn nextjournal.clerk.render/render-coll} {:closing-paren "
)"
:name nextjournal.clerk.viewer/sequential-viewer :opening-paren "
("
:page-size 20 :pred #object[clojure.core$sequential_QMARK_ 0x8b96e9f "
clojure.core$sequential_QMARK_@8b96e9f"
] :render-fn nextjournal.clerk.render/render-coll} {:closing-paren "
}"
:name nextjournal.clerk.viewer/map-viewer :opening-paren "
{"
:page-size 10 :pred #object[clojure.core$map_QMARK___5429 0x4ed20e7d "
clojure.core$map_QMARK___5429@4ed20e7d"
] :render-fn nextjournal.clerk.render/render-map} {:add-viewers [{:name nextjournal.clerk.viewer/string-viewer :page-size 80 :pred #object[clojure.core$string_QMARK___5427 0x7577b641 "
clojure.core$string_QMARK___5427@7577b641"
] :render-fn nextjournal.clerk.render/render-string} {:name nextjournal.clerk.viewer/number-viewer :pred #object[clojure.core$number_QMARK_ 0x1af1dcac "
clojure.core$number_QMARK_@1af1dcac"
] :render-fn nextjournal.clerk.render.table/render-table-number :transform-fn #object[nextjournal.clerk.viewer$update_val$fn__15695 0x20850ecd "
nextjournal.clerk.viewer$update_val$fn__15695@20850ecd"
]} {:name nextjournal.clerk.viewer/elision-viewer :render-fn nextjournal.clerk.render.table/render-table-elision :transform-fn #object[nextjournal.clerk.viewer$mark_presented 0x6e581630 "
nextjournal.clerk.viewer$mark_presented@6e581630"
]} {:name nextjournal.clerk.viewer/table-missing-viewer :pred #{:nextjournal/missing} :render-fn (fn [x] [:<>])} {:name nextjournal.clerk.viewer/table-markup-viewer :render-fn nextjournal.clerk.render.table/render-table-markup} {:name nextjournal.clerk.viewer/table-head-viewer :render-fn nextjournal.clerk.render.table/render-table-head} {:name nextjournal.clerk.viewer/table-body-viewer :render-fn nextjournal.clerk.render.table/render-table-body} {2 more elided}] :name nextjournal.clerk.viewer/table-viewer :page-size 20 :transform-fn #object[nextjournal.clerk.viewer$fn__15952 0x6043dfeb "
nextjournal.clerk.viewer$fn__15952@6043dfeb"
]})

Now let's display our clojure-data var from above using these modified viewers.
(clerk/with-viewers viewers-without-lazy-loading
  clojure-data)
{:hello "
world 👋"
:tacos ((🌮) (🌮 🌮) (🌮 🌮 🌮) (🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮) (🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮 🌮)) :zeta "
The↩︎purpose↩︎of↩︎visualization↩︎is↩︎insight,↩︎not↩︎pictures."}
👷 Loading Libraries

Here is a custom viewer for Mermaid, a markdown-like syntax for creating diagrams from text. Note that this library isn't bundled with Clerk but we use a component based on d3-require to load it at runtime.
(def mermaid-viewer
  {:transform-fn clerk/mark-presented
   :render-fn '(fn [value]
                 (when value
                   [nextjournal.clerk.render/with-d3-require {:package ["mermaid@8.14/dist/mermaid.js"]}
                    (fn [mermaid]
                      [:div {:ref (fn [el] (when el
                                             (.render mermaid (str (gensym)) value #(set! (.-innerHTML el) %))))}])]))})
{:render-fn (fn [value] (when value [nextjournal.clerk.render/with-d3-require {:package ["
mermaid@8.14/dist/mermaid.js"]}
(fn [mermaid] [:div {:ref (fn [el] (when el (.render mermaid (str (gensym)) value (fn* [%1] (set! (.-innerHTML el) %1)))))}])])) :transform-fn #object[nextjournal.clerk$mark_presented 0x6fc09c54 "
nextjournal.clerk$mark_presented@6fc09c54"
]}

We can then use the above viewer using with-viewer.
(clerk/with-viewer mermaid-viewer
  "stateDiagram-v2
    [*] --> Still
    Still --> [*]

    Still --> Moving
    Moving --> Still
    Moving --> Crash
    Crash --> [*]")
Still
Moving
Crash
🧙 Evaluator

By default, SCI is used for evaluating :render-fn functions in the browser.

What follows is an intentionally inefficient but fun way to compute the nth fibonacci number and show how long it took.
(def fib-viewer
  {:render-fn '(fn [n opts]
                 (reagent.core/with-let
                   [fib (fn fib [x]
                          (if (< x 2)
                            1
                            (+ (fib (dec x)) (fib (dec (dec x))))))
                    time-before (js/performance.now)
                    nth-fib (fib n)
                    time-after (js/performance.now)]
                   [:div
                    [:p
                     (if (= :cherry (-> opts :viewer :render-evaluator))
                       "Cherry"
                       "SCI")
                     " computed the " n "th fibonacci number (" nth-fib ")"
                     " in " (js/Math.ceil (- time-after time-before) 2) "ms."]]))})
{:render-fn (fn [n opts] (reagent.core/with-let [fib (fn fib [x] (if (< x 2) 1 (+ (fib (dec x)) (fib (dec (dec x)))))) time-before (js/performance.now) nth-fib (fib n) time-after (js/performance.now)] [:div [:p (if (= :cherry (-> opts :viewer :render-evaluator)) "
Cherry"
"
SCI")
"
 computed the "
n "
th fibonacci number ("
nth-fib "
)"
"
 in "
(js/Math.ceil (- time-after time-before) 2) "
ms."]]))}
(clerk/with-viewer fib-viewer 25)

SCI computed the 25th fibonacci number (121393) in 56ms.

You can opt into cherry as an alternative evaluator by setting {::clerk/render-evaluator :cherry} via the viewers opts (see Customizations). The main difference between cherry and SCI for viewer functions is performance. For performance-sensitive code cherry is better suited since it compiles directly to JavaScript code.
(clerk/with-viewer fib-viewer {::clerk/render-evaluator :cherry} 25)

Cherry computed the 25th fibonacci number (121393) in 2ms.
⚙️ Customizations

Clerk allows easy customization of visibility, result width and budget. All settings can be applied document-wide using ns metadata or a top-level settings marker and per form using metadata.

Let's start with a concrete example to understand how this works.
🙈 Visibility

By default, Clerk will show all code and results for a notebook.

You can use a map of the following shape to set the visibility of code and results individually:
{:nextjournal.clerk/visibility {:code :hide :result :show}}

The above example will hide the code and show only results.

Valid values are :show, :hide and :fold (only available for :code). Using {:code :fold} will hide the code cell initially but show an indicator to toggle its visibility:
show code
[8 4 6 17 19 2 18 9 23 11 14 24 21 7 3 5 13 12 15 22 5 more elided]

The visibility map can be used in the following ways:

Set document defaults via the ns form
(ns visibility
  {:nextjournal.clerk/visibility {:code :fold}})

The above example will hide all code cells by default but show an indicator to toggle their visibility instead. In this case results will always show because that’s the default.

As metadata to control a single top-level form
^{::clerk/visibility {:code :hide}} (shuffle (range 25))

This will hide the code but only show the result:
[8 4 6 17 19 2 18 9 23 11 14 24 21 7 3 5 13 12 15 22 5 more elided]

Setting visibility as metadata will override the document-wide visibility settings for this one specific form.

As top-level form to change the document defaults

Independently of what defaults are set via your ns form, you can use a top-level map as a marker to override the visibility settings for any forms following it.

Example: Code is hidden by default but you want to show code for all top-level forms after a certain point:
(ns visibility
  {:nextjournal.clerk/visibility {:code :hide}})

(+ 39 3) ;; code will be hidden
(range 25) ;; code will be hidden

{:nextjournal.clerk/visibility {:code :show}}

(range 500) ;; code will be visible
(rand-int 42) ;; code will be visible

This comes in quite handy for debugging too!
👻 Clerk Metadata

By default, Clerk will hide Clerk's metadata annotations on cells to not distract from the essence. When you do want your reader learn how the metadata annotations are written – as for this book – you can opt out of this behaviour by modifying the code-block-viewer:
(clerk/add-viewers! [(assoc v/code-block-viewer :transform-fn (v/update-val :text))])
🍽 Table of Contents

If you want a table of contents like the one in this document, set the :nextjournal.clerk/toc option.
(ns doc-with-table-of-contents
  {:nextjournal.clerk/toc true})

If you want it to be collapsed initially, use :collapsed as a value.
🔮 Result Expansion

If you want to better see the shape of your data without needing to click and expand it first, set the :nextjournal.clerk/auto-expand-results? option.
show code
({:dice [2 4 6 1 5 3] :name "
Karen Meyer"
:role :admin} {:dice [1 4 6 5 2 3] :name "
Karen Stasčnyk"
:role :designer} {:dice [4 6 2 3 1 5] :name "
Vlad Stasčnyk"
:role :programmer} {:dice [3 6 4 5 1 2] :name "
Karen Miller"
:role :programmer} {:dice [3 6 2 1 5 4] :name "
Oscar Ronin"
:role :admin} {:dice [4 5 6 2 3 1] :name "
Oscar Black"
:role :programmer} {:dice [4 5 2 3 1 6] :name "
Vlad Meyer"
:role :designer} {:dice [3 5 1 4 2 6] :name "
Rebecca Black"
:role :programmer} {:dice [6 1 2 4 3 5] :name "
Conrad Black"
:role :operator} {:dice [1 5 6 3 2 4] :name "
Rebecca Black"
:role :designer} {:dice [2 1 5 6 4 3] :name "
Rebecca Stasčnyk"
:role :admin} {:dice [6 3 1 5 4 2] :name "
Karen Meyer"
:role :operator} {:dice [1 4 3 3 more elided] :name "
Oscar Meyer"
:role :operator} {3 more elided} {3 more elided})
^{::clerk/auto-expand-results? true} rows
({:dice [2 4 6 1 5 3] :name "
Karen Meyer"
:role :admin}
 {:dice [1 4 6 5 2 3] :name "
Karen Stasčnyk"
:role :designer}
 {:dice [4 6 2 3 1 5] :name "
Vlad Stasčnyk"
:role :programmer}
 {:dice [3 6 4 5 1 2] :name "
Karen Miller"
:role :programmer}
 {:dice [3 6 2 1 5 4] :name "
Oscar Ronin"
:role :admin}
 {:dice [4 5 6 2 3 1] :name "
Oscar Black"
:role :programmer}
 {:dice [4 5 2 3 1 6] :name "
Vlad Meyer"
:role :designer}
 {:dice [3 5 1 4 2 6] :name "
Rebecca Black"
:role :programmer}
 {:dice [6 1 2 4 3 5] :name "
Conrad Black"
:role :operator}
 {:dice [1 5 6 3 2 4] :name "
Rebecca Black"
:role :designer}
 {:dice [2 1 5 6 4 3] :name "
Rebecca Stasčnyk"
:role :admin}
 {:dice [6 3 1 5 4 2] :name "
Karen Meyer"
:role :operator}
 {:dice [1 4 3 3 more elided] :name "
Oscar Meyer"
:role :operator}
 {3 more elided}
 {3 more elided})

This option might become the default in the future.
🙅🏼‍♂️ Viewer Budget

In order to not send too much data to the browser, Clerk uses a per-result budget to limit. You can see this budget in action above. Use the :nextjournal.clerk/budget key to change its default value of 200 or disable it completely using nil.
^{::clerk/budget nil ::clerk/auto-expand-results? true} rows
({:dice [2 4 6 1 5 3] :name "
Karen Meyer"
:role :admin}
 {:dice [1 4 6 5 2 3] :name "
Karen Stasčnyk"
:role :designer}
 {:dice [4 6 2 3 1 5] :name "
Vlad Stasčnyk"
:role :programmer}
 {:dice [3 6 4 5 1 2] :name "
Karen Miller"
:role :programmer}
 {:dice [3 6 2 1 5 4] :name "
Oscar Ronin"
:role :admin}
 {:dice [4 5 6 2 3 1] :name "
Oscar Black"
:role :programmer}
 {:dice [4 5 2 3 1 6] :name "
Vlad Meyer"
:role :designer}
 {:dice [3 5 1 4 2 6] :name "
Rebecca Black"
:role :programmer}
 {:dice [6 1 2 4 3 5] :name "
Conrad Black"
:role :operator}
 {:dice [1 5 6 3 2 4] :name "
Rebecca Black"
:role :designer}
 {:dice [2 1 5 6 4 3] :name "
Rebecca Stasčnyk"
:role :admin}
 {:dice [6 3 1 5 4 2] :name "
Karen Meyer"
:role :operator}
 {:dice [1 4 3 6 5 2] :name "
Oscar Meyer"
:role :operator}
 {:dice [6 3 4 1 2 5] :name "
Vlad Ronin"
:role :designer}
 {:dice [3 5 6 4 1 2] :name "
Karen Miller"
:role :programmer})
⚛️ Clerk Sync

Clerk Sync is a way to support lightweight interactivity between Clerk's render display running in the browser and the JVM. By flagging a form defining an atom with ::clerk/sync metadata, Clerk will sync this atom to Clerk's render environment. It will also watch recompute the notebook whenever the value inside the atom changes.
^{::clerk/sync true}
(defonce !counter (atom 0))
#object[clojure.lang.Atom 0x4d430c7c {:status :ready :val 0}]
(clerk/with-viewer {:render-fn '(fn [] [:button.bg-sky-500.hover:bg-sky-700.text-white.rounded-xl.px-2.py-1
                                        {:on-click #(swap! !counter inc)}
                                        "Increment Counter"])}
  {})
🚰 Tap Inspector

Clerk comes with an inspector notebook for Clojure's tap system. Use the following form from your REPL to show it.
(nextjournal.clerk/show! 'nextjournal.clerk.tap)

You can then call tap> from anywhere in your codebase and the Tap Inspector will show your value. This supports the full viewer api described above.
(tap> (clerk/html [:h1 "Hello 🚰 Tap Inspector 👋"]))
👷‍♀️ Static Building

Clerk can make a static HTML build from a collection of notebooks. The entry point for this is the nextjournal.clerk/build! function. You can pass it a set of notebooks via the :paths option (also supporting glob patterns).

When Clerk is building multiple notebooks, it will automatically generate an index page that will be the first to show up when opening the build. You can override this index page via the :index option.

Also notably, there is a :compile-css option which compiles a css file containing only the used CSS classes from the generated markup. (Otherwise, Clerk is using Tailwind's Play CDN script which can make the page flicker, initially.)

If set, the :ssr option will use React's server-side-rendering to include the generated markup in the build HTML.

For a full list of options see the docstring in nextjournal.clerk/build!.

Here are some examples:
;; Building a single notebook
(clerk/build! {:paths ["notebooks/rule_30.clj"]})

;; Building all notebooks in `notebook/` with a custom index page.
(clerk/build! {:paths ["notebooks/*"]
               :index "notebooks/welcome.clj"})
⚡️ Render nREPL

For interactive development of :render-fns, Clerk comes with a Render nREPL server. To enable it, pass the :render-nrepl option to serve!. You can change the default port 1339 by passing a different :port number.
(nextjournal.clerk/serve! {:render-nrepl {}})

    nREPL server started on port 1339...

⚠️ Editor Connection Tips

Cider

    Run M-x cider-connect-cljs
    Select localhost
    Enter 1339 for the port
    Select nbb repl type
    Open a ClojureScript buffer and run M-x sesman-link-with-buffer selecting the newly connected repl.

Calva

    Connect to a Running REPL Server, not in the Project
    Select nbb for Project Type/Connect Sequence
    Enter localhost:1339 (or the custom port)

🤖 How Clerk Works
🔖 Parsing

First, we parse a given Clojure file using rewrite-clj.
(def parsed
  (parser/parse-file "book.clj"))
{:blocks [{:loc {:column 1 :end-column 28 :end-line 21 :line 2} :text "
^{:nextjournal.clerk/visibility {:code :hide}}↩︎(ns nextjournal.clerk.book↩︎  {:ne803 more elided"
:type :code} {:loc {:column 1 :end-column 75 :end-line 149 :line 146} :text "
(def clojure-data↩︎  {:hello "world 👋"↩︎   :tacos (map #(repeat % '🌮) (range 1 378 more elided"
:type :code} {:loc {:column 1 :end-column 8 :end-line 153 :line 153} :text "
(range)"
:type :code} {:loc {:column 1 :end-column 50 :end-line 155 :line 155} :text "
(def fib (lazy-cat [0 1] (map + fib (rest fib))))"
:type :code} {:loc {:column 1 :end-column 71 :end-line 164 :line 164} :text "
(clerk/html [:div "As Clojurians we " [:em "really"] " enjoy hiccup"])"
:type :code} {:loc {:column 1 :end-column 46 :end-line 167 :line 167} :text "
(clerk/html "Never <strong>forget</strong>.")"
:type :code} {:loc {:column 1 :end-column 100 :end-line 170 :line 170} :text "
(clerk/html [:button.bg-sky-500.hover:bg-sky-700.text-white.rounded-xl.px-2.py-119 more elided"
:type :code} {:loc {:column 1 :end-column 60 :end-line 175 :line 173} :text "
(clerk/html [:svg {:width 500 :height 100}↩︎             [:circle {:cx  25 :cy 5081 more elided"
:type :code} {:loc {:column 1 :end-column 43 :end-line 181 :line 179} :text "
(clerk/html [:div.flex.justify-center.space-x-6↩︎             [:p "a table is nex52 more elided"
:type :code} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} {3 more elided} 83 more elided] :file "
book.clj"}
🧐 Analysis

Then, each expression is analysed using tools.analyzer. A dependency graph, the analyzed form and the originating file is recorded.
(def analyzed
  (ana/build-graph parsed))
{:->analysis-info {AlwaysArrayMap {} BufferedImage {} Exception {} IllegalArgumentException {} JarFile {} Object {} PngEncoder {} Throwable {} URI {} URL {} 1216 more elided} :blocks [{:file "
book.clj"
:form (ns nextjournal.clerk.book {:nextjournal.clerk/open-graph {:description "
Clerk’s official documentation."
:image "
https://cdn.nextjournal.com/data/QmbHy6nYRgveyxTvKDJvyy2VF9teeXYkAXXDbgbKZK6YRC?58 more elided"
:title "
The Book of Clerk"
:url "
https://book.clerk.vision"}
:nextjournal.clerk/toc true} (:require [clojure.string :as str] [next.jdbc :as jdbc] [nextjournal.clerk :as clerk] [nextjournal.clerk.parser :as parser] [nextjournal.clerk.eval :as eval] [nextjournal.clerk.analyzer :as ana] [nextjournal.clerk.viewer :as v] [emmy.env :as emmy] [emmy.expression] [weavejester.dependency :as dep]) (:import (javax.imageio ImageIO) (java.net URL))) :freezable? true :id nextjournal.clerk.book/anon-expr-5drrucernGDkF5geFbkQukAnbb3fXg :loc {:column 1 :end-column 28 :end-line 21 :line 2} :ns? true :settings {:nextjournal.clerk/visibility {:code :hide :result :hide}} :text "
^{:nextjournal.clerk/visibility {:code :hide}}↩︎(ns nextjournal.clerk.book↩︎  {:ne803 more elided"
:text-without-meta "
(ns nextjournal.clerk.book↩︎  {:nextjournal.clerk/toc true↩︎   :nextjournal.clerk/756 more elided"
:type :code} {:file "
book.clj"
:form (def clojure-data {:hello "
world 👋"
:tacos (map (fn* [%1] (repeat %1 (quote 🌮))) (range 1 30)) :zeta "
The↩︎purpose↩︎of↩︎visualization↩︎is↩︎insight,↩︎not↩︎pictures."})
:freezable? true :id nextjournal.clerk.book/clojure-data :loc {4 more elided} :settings {1 more elided} :text "
(def clojure-data↩︎  {:hello "world 👋"↩︎   :tacos (map #(repeat % '🌮) (range 1 378 more elided"
:text-without-meta "
(def clojure-data↩︎  {:hello "world 👋"↩︎   :tacos (map #(repeat % '🌮) (range 1 378 more elided"
:type :code :var nextjournal.clerk.book/clojure-data 2 more elided} {9 more elided} {12 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {9 more elided} {12 more elided} {9 more elided} {9 more elided} {9 more elided} 83 more elided] :error-on-missing-vars :on :file "
book.clj"
:graph {2 more elided} :ns #object[clojure.lang.Namespace 0xe4d4edd "
nextjournal.clerk.book"
] :ns? true :open-graph {5 more elided} :redefs #{} :toc-visibility true 1 more elided}

This analysis is done recursively, descending into all dependency symbols.
(ana/find-location 'nextjournal.clerk.analyzer/analyze-file)
"
/mnt/gitlibs-cache/libs/io.github.nextjournal/clerk/8a5aadf9cc332741bf26a5201c4547 more elided"
(ana/find-location `dep/depend)
"
/mnt/mvn-cache/weavejester/dependency/0.2.1/dependency-0.2.1.jar"
(ana/find-location 'io.methvin.watcher.DirectoryChangeEvent)
"
/mnt/mvn-cache/io/methvin/directory-watcher/0.17.3/directory-watcher-0.17.3.jar"
(ana/find-location 'java.util.UUID)
nil
(let [{:keys [graph]} analyzed]
  (dep/transitive-dependencies graph 'nextjournal.clerk.book/analyzed))
#{Exception IllegalArgumentException JarFile clojure.lang.IObj clojure.lang.Numbers clojure.lang.PersistentHashMap clojure.lang.RT clojure.lang.Util clojure.lang.Var java.lang.AssertionError java.lang.IllegalArgumentException java.net.URL java.util.jar.JarFile java.util.zip.ZipFile babashka.fs/absolute? babashka.fs/cwd babashka.fs/exists? babashka.fs/file babashka.fs/file-separator babashka.fs/normalize 283 more elided}
🪣 Hashing

Then we can use this information to hash each expression.
(def hashes
  (:->hash (ana/hash analyzed)))
{AlwaysArrayMap "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
BufferedImage "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
Exception "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
IllegalArgumentException "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
JarFile "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
Object "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
PngEncoder "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
Throwable "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
URI "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
URL "
5dsgvkMmMLJgogxVktTjrTwjJ4afJa"
1201 more elided}
🗃 Cached Evaluation

Clerk uses the hashes as filenames and only re-evaluates forms that haven't been seen before. The cache is using nippy.
(def rand-fifteen
  (do (Thread/sleep 10)
      (shuffle (range 15))))
[14 0 12 3 13 4 6 7 1 10 5 8 11 9 2]

We can look up the cache key using the var name in the hashes map.
(when-let [form-hash (get hashes 'nextjournal.clerk.book/rand-fifteen)]
  (let [hash (slurp (eval/->cache-file (str "@" form-hash)))]
    (eval/thaw-from-cas hash)))
[0 5 8 6 7 2 1 11 10 4 9 12 3 14 13]

As an escape hatch, you can tag a form or var with ::clerk/no-cache to always re-evaluate it. The following form will never be cached.
^::clerk/no-cache (shuffle (range 42))
[3 35 6 2 30 38 11 26 12 14 9 22 27 37 36 5 31 4 28 41 22 more elided]

For side effectful functions that should be cached, like a database query, you can add a value like this #inst to control when evaluation should happen.
(def query-results
  (let [_run-at #_(java.util.Date.) #inst "2021-05-20T08:28:29.445-00:00"
        ds (next.jdbc/get-datasource {:dbtype "sqlite" :dbname "chinook.db"})]
    (with-open [conn (next.jdbc/get-connection ds)]
      (clerk/table (next.jdbc/execute! conn ["SELECT AlbumId, Bytes, Name, TrackID, UnitPrice FROM tracks"])))))
:tracks/AlbumId	:tracks/Bytes	:tracks/Name	:tracks/TrackId	:tracks/UnitPrice
1	11170334	For Those About To Rock (We Salute You)	1	0.99
2	5510424	Balls to the Wall	2	0.99
3	3990994	Fast As a Shark	3	0.99
3	4331779	Restless and Wild	4	0.99
3	6290521	Princess of the Dawn	5	0.99
1	6713451	Put The Finger On You	6	0.99
1	7636561	Let's Get It Up	7	0.99
1	6852860	Inject The Venom	8	0.99
1	6599424	Snowballed	9	0.99
1	8611245	Evil Walks	10	0.99
1	6566314	C.O.D.	11	0.99
1	8596840	Breaking The Rules	12	0.99
1	6706347	Night Of The Long Knives	13	0.99
1	8817038	Spellbound	14	0.99
4	10847611	Go Down	15	0.99
4	7032162	Dog Eat Dog	16	0.99
4	12021261	Let There Be Rock	17	0.99
4	8776140	Bad Boy Boogie	18	0.99
4	10617116	Problem Child	19	0.99
4	12066294	Overdose	20	0.99
3483 more elided