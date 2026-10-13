(ns resolver-sim.notebook-support.theme
  "Shared design tokens and style helpers for all Clerk notebooks.

  Every colour, border, spacing, and tone value lives here.
  Update a value in notebook-theme to change every notebook that uses it.")

(def notebook-theme
  {:surface/default "#ffffff"
   :surface/subtle "#f8fafc"
   :surface/light "#fafafa"
   :surface/body "#f5f5f5"

   :text/body "#334155"
   :text/muted "#64748b"
   :text/subtle "#cbd5e1"
   :text/dim "#f1f5f9"
   :text/dark "#111827"

   :tone/red-bg "#fff7f7"
   :tone/red-border "#fecaca"
   :tone/red-text "#991b1b"
   :tone/red-row-bg "#fef2f2"

   :tone/amber-bg "#fffbeb"
   :tone/amber-border "#fde68a"
   :tone/amber-text "#92400e"
   :tone/amber-row-bg "#fff7ed"

   :tone/green-bg "#f0fdf4"
   :tone/green-border "#bbf7d0"
   :tone/green-text "#166534"

   :tone/neutral-bg "#ffffff"
   :tone/neutral-border "#e2e8f0"
   :tone/neutral-text "#334155"

   :info/bg "#eff6ff"
   :info-border "#93c5fd"
   :info-text "#1e3a8a"

   :alert/amber-bg "#fffbeb"
   :alert/amber-border "#f59e0b"

   :alert/orange-bg "#fff7ed"
   :alert/orange-border "#fdba74"
   :alert/orange-text "#7c2d12"
   :alert/orange-border-light "#fed7aa"
   :alert/orange-text-muted "#9a3412"

   :alert/green-bg "#f0fdf4"
   :alert/green-border "#16a34a"
   :alert/green-text "#14532d"
   :alert/green-text2 "#15803d"

   :alert/red-bg "#fef2f2"
   :alert/red-border "#dc2626"

   :coverage/unhit-bg "#fffbeb"
   :coverage/unhit-text "#78350f"
   :coverage/unhit-border "#d97706"

   :table/header-bg "#f3f4f6"
   :table/header-blue-bg "#dbeafe"
   :table/header-orange-bg "#fff7ed"
   :table/header-text "#111827"
   :table/border "#e5e7eb"
   :table/row-border "#e5e7eb"
   :table/border-blue "#bfdbfe"
   :table/cell-text "#374151"

   :status/passed-bg "#dcfce7"
   :status/passed-text "#166534"
   :status/failed-bg "#fee2e2"
   :status/failed-text "#991b1b"
   :status/warning-bg "#fef3c7"
   :status/warning-text "#92400e"
   :status/neutral-bg "#f3f4f6"
   :status/neutral-text "#374151"
   :status/pass-color "#16a34a"
   :status/fail-color "#dc2626"
   :status/atk-color "#d97706"

   :domain/sew-color "#6b7280"
   :domain/yield-color "#0891b2"

   :jumpbar/bg "#ffffff"
   :jumpbar-border "#e2e8f0"
   :repro-header-bg "#f3f4f6"

   :code/block-bg "#0f172a"
   :code/block-text "#38bdf8"})

(defn tone-style
  "Style map for the given section/row tone keyword.
   Sets background, border, and text colours."
  [tone]
  (case tone
    :red {:backgroundColor (:tone/red-bg notebook-theme)
          :borderColor     (:tone/red-border notebook-theme)
          :color           (:tone/red-text notebook-theme)}
    :amber {:backgroundColor (:tone/amber-bg notebook-theme)
            :borderColor     (:tone/amber-border notebook-theme)
            :color           (:tone/amber-text notebook-theme)}
    :green {:backgroundColor (:tone/green-bg notebook-theme)
            :borderColor     (:tone/green-border notebook-theme)
            :color           (:tone/green-text notebook-theme)}
    {:backgroundColor (:tone/neutral-bg notebook-theme)
     :borderColor     (:tone/neutral-border notebook-theme)
     :color           (:tone/neutral-text notebook-theme)}))

(defn section-style
  "Section container style with tone-specific background, border, text."
  [tone]
  (merge
   {:marginBottom "10px"
    :border       "1px solid"
    :borderRadius "6px"
    :padding      "8px 10px"}
   (tone-style tone)))

(def table-style
  {:borderCollapse "collapse"
   :width          "100%"
   :fontSize       "0.9em"})

(def table-compact-style
  (assoc table-style :fontSize "0.84em"))

(def table-small-style
  (assoc table-style :fontSize "0.8em"))

(def table-tight-style
  (assoc table-style :fontSize "0.85em"))

(def table-header-row-style
  {:background  (:table/header-bg notebook-theme)
   :color       (:table/header-text notebook-theme)
   :textAlign   "left"})

(def table-header-cell-style
  {:padding      "6px 8px"
   :borderBottom (str "1px solid " (:table/border notebook-theme))
   :color        (:table/header-text notebook-theme)})

(def table-cell-style
  {:padding      "6px 8px"
   :borderBottom (str "1px solid " (:table/row-border notebook-theme))
   :color        (:table/cell-text notebook-theme)})

(def table-cell-compact-style
  (assoc table-cell-style :padding "5px 8px"))

(defn status-style
  "Style map for a status badge based on the status value."
  [status]
  (case status
    (:passed "passed" :pass)  {:backgroundColor (:status/passed-bg notebook-theme)
                               :color           (:status/passed-text notebook-theme)}
    (:failed "failed" :fail)  {:backgroundColor (:status/failed-bg notebook-theme)
                               :color           (:status/failed-text notebook-theme)}
    (:warning "warning" :warn) {:backgroundColor (:status/warning-bg notebook-theme)
                                :color           (:status/warning-text notebook-theme)}
    {:backgroundColor (:status/neutral-bg notebook-theme)
     :color           (:status/neutral-text notebook-theme)}))

(def status-badge-base-style
  {:display     "inline-block"
   :padding     "2px 6px"
   :borderRadius "999px"
   :fontSize    "0.8em"
   :fontWeight  "600"})

(defn kind-badge-style
  "Style map for a status-kind badge based on the kind label string."
  [kind-str]
  (case kind-str
    "Validation"
    {:backgroundColor (:status/neutral-bg notebook-theme)
     :color           (:status/neutral-text notebook-theme)}
    "Research finding"
    {:backgroundColor (:status/neutral-bg notebook-theme)
     :color           (:status/neutral-text notebook-theme)}
    "Expected negative"
    {:backgroundColor (:status/warning-bg notebook-theme)
     :color           (:status/warning-text notebook-theme)}
    "Missing data"
    {:backgroundColor (:status/warning-bg notebook-theme)
     :color           (:status/warning-text notebook-theme)}
    {:backgroundColor (:status/neutral-bg notebook-theme)
     :color           (:status/neutral-text notebook-theme)}))
