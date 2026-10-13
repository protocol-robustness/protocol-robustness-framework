/* Assurance Lab UI — vanilla JS, no framework. */
(function () {
  "use strict";

  var API = "/api/lab";

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === "class") node.className = attrs[k];
        else if (k === "text") node.textContent = attrs[k];
        else if (k.slice(0, 2) === "on") node.addEventListener(k.slice(2), attrs[k]);
        else node.setAttribute(k, attrs[k]);
      });
    }
    (children || []).forEach(function (c) {
      if (c) node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return node;
  }

  function fmt(n) {
    if (n === null || n === undefined) return "—";
    if (typeof n === "number") return n.toLocaleString("en-US");
    return String(n);
  }

  function fmtRate(r) {
    if (r === null || r === undefined) return "—";
    if (Array.isArray(r)) return r.join("/");
    return fmt(r);
  }

  function badge(status) {
    var s = String(status || "inconclusive");
    var cls = { pass: "badge-pass", fail: "badge-fail" }[s] || "badge-inconclusive";
    return el("span", { class: "badge " + cls, text: s.toUpperCase() });
  }

  function statusBadge(status) {
    var labels = { solvent: "SOLVENT", impaired: "IMPAIRED", insolvent: "INSOLVENT",
                   unassessable: "UNASSESSABLE", "assessment-invalid": "ASSESSMENT INVALID",
                   "fully-served": "FULLY SERVED", shortfall: "SHORTFALL" };
    var cls = { solvent: "badge-pass", "fully-served": "badge-pass",
                impaired: "badge-inconclusive", shortfall: "badge-inconclusive",
                insolvent: "badge-fail", "assessment-invalid": "badge-fail",
                unassessable: "badge-inconclusive" };
    var s = String(status);
    return el("span", { class: "badge " + (cls[s] || "badge-inconclusive"),
                        text: labels[s] || s.toUpperCase() });
  }

  var app = document.getElementById("app");

  function renderHome() {
    fetch(API + "/experiments")
      .then(function (r) { return r.json(); })
      .then(function (data) {
        app.innerHTML = "";
        app.appendChild(el("div", { class: "hero" }, [
          el("h1", { text: "Making the future of protocols visible" }),
          el("p", { class: "tagline", text:
            "Explore what happens when protocol assumptions change. " +
            "Each run executes real Protocol Robustness Framework research — no install, no terminal, no PRF knowledge required." })
        ]));
        var grid = el("div", { class: "experiment-grid" });
        data.experiments.forEach(function (x) {
          grid.appendChild(el("a", { class: "experiment-card", href: "./#/experiment/" + x.experiment_slug }, [
            el("p", { class: "x-title", text: x.experiment_title }),
            el("p", { class: "x-question", text: x.experiment_question }),
            el("div", { class: "x-meta", text: x.experiment_ref + " · " + x.experiment_protocol })
          ]));
        });
        app.appendChild(grid);
        app.appendChild(el("h2", { text: "Why run experiments here?" }));
        app.appendChild(el("p", { text:
          "The lab is a thin visitor layer over the Protocol Robustness Framework. " +
          "You choose an experiment and a few assumptions; the lab validates your inputs, " +
          "executes the framework's own mechanism on the AWS runner, and returns the " +
          "structured outcome, the assurance findings the framework produced, and the " +
          "evidence commitments that bind the result." }));
        app.appendChild(el("p", { text:
          "You can change the assumptions and rerun — that is the point: same mechanism, " +
          "different assumption, different outcome, and the evidence shows why." }));
      })
      .catch(function () {
        app.appendChild(el("div", { class: "error-box", text:
          "Could not reach the Assurance Lab service. The dynamic endpoint may not be running." }));
      });
  }

  function paramInput(p) {
    var id = "p-" + p.parameter_id;
    if (p.parameter_type === "enum") {
      var sel = el("select", { id: id });
      p.parameter_options.forEach(function (o) {
        sel.appendChild(el("option", { value: o, text: o }));
        if (o === p.parameter_default) sel.value = o;
      });
      return sel;
    }
    if (p.parameter_type === "boolean") {
      var cb = el("input", { type: "checkbox", id: id });
      if (p.parameter_default) cb.checked = true;
      return cb;
    }
    var input = el("input", { type: "number", id: id });
    if (p.parameter_min !== null && p.parameter_min !== undefined) input.min = p.parameter_min;
    if (p.parameter_max !== null && p.parameter_max !== undefined) input.max = p.parameter_max;
    input.value = p.parameter_default !== null && p.parameter_default !== undefined
      ? String(p.parameter_default) : "";
    if (p.parameter_optional) input.placeholder = "optional";
    return input;
  }

  function collectParams(experiment) {
    var params = {};
    experiment.parameters.forEach(function (p) {
      var input = document.getElementById("p-" + p.parameter_id);
      if (!input) return;
      if (p.parameter_type === "boolean") params[p.parameter_id] = input.checked;
      else if (p.parameter_type === "enum") params[p.parameter_id] = input.value;
      else {
        var v = input.value.trim();
        params[p.parameter_id] = v === "" ? null : Number(v);
      }
    });
    return params;
  }

  function renderForm(experiment, opts) {
    opts = opts || {};
    var form = el("div", { class: "panel" });
    form.appendChild(el("p", { class: "question", text: experiment.experiment_question }));
    form.appendChild(el("p", { text: experiment.experiment_description }));
    form.appendChild(el("p", { class: "hint", text: "Mechanism: " + experiment.experiment_mechanism }));

    var fields = el("div");
    experiment.parameters.forEach(function (p) {
      fields.appendChild(el("div", { class: "field" }, [
        el("label", { for: "p-" + p.parameter_id, text: p.parameter_label + (p.parameter_optional ? " (optional)" : "") }),
        paramInput(p)
      ]));
    });
    form.appendChild(fields);

    var statusLine = el("p", { class: "status-line" });
    var runBtn = el("button", {
      class: "run", text: "Run experiment",
      onclick: function () {
        runBtn.disabled = true;
        statusLine.textContent = "Executing on the AWS runner…";
        var body = { experiment: experiment.experiment_ref, parameters: collectParams(experiment) };
        postRun(body, function (result) {
          runBtn.disabled = false;
          renderResult(result);
        }, function (err) {
          runBtn.disabled = false;
          statusLine.textContent = "";
          app.appendChild(el("div", { class: "error-box", text: err }));
        });
      }
    });
    var actions = el("div", { class: "actions" }, [runBtn]);

    if (experiment.experiment_comparison) {
      var cmpBtn = el("button", {
        class: "secondary", text: "Compare mechanisms (FCFS vs pro-rata)",
        onclick: function () {
          runBtn.disabled = true; cmpBtn.disabled = true;
          statusLine.textContent = "Running both mechanisms…";
          var base = collectParams(experiment);
          var a = { experiment: experiment.experiment_ref, parameters: Object.assign({}, base, { mechanism: "fcfs" }) };
          var b = { experiment: experiment.experiment_ref, parameters: Object.assign({}, base, { mechanism: "pro-rata" }) };
          runBoth([a, b], function (results) {
            runBtn.disabled = false; cmpBtn.disabled = false;
            renderComparison(experiment, results);
          }, function (err) {
            runBtn.disabled = false; cmpBtn.disabled = false;
            app.appendChild(el("div", { class: "error-box", text: err }));
          });
        }
      });
      actions.appendChild(cmpBtn);
    }

    form.appendChild(actions);
    form.appendChild(statusLine);
    return form;
  }

  function renderExperiment(slug) {
    fetch(API + "/experiments/" + slug)
      .then(function (r) { return r.json(); })
      .then(function (data) {
        app.innerHTML = "";
        var x = data.experiment;
        app.appendChild(el("p", { class: "hint", text: "Experiment · " + x.experiment_ref }));
        app.appendChild(el("h2", { text: x.experiment_title }));
        app.appendChild(renderForm(x));
        if (x.experiment_comparison) {
          app.appendChild(el("div", { class: "result-section" }, [
            el("h3", { text: "Why compare?" }),
            el("p", { text:
              "The same demand and the same available liquidity can produce different outcomes " +
              "depending on the allocation mechanism. Change the numbers, run both, and watch the " +
              "same obligations resolve differently." })
          ]));
        }
      })
      .catch(function () {
        app.appendChild(el("div", { class: "error-box", text: "Unknown experiment: " + slug }));
      });
  }

  function postRun(body, ok, err) {
    fetch(API + "/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    }).then(function (r) { return r.json().then(function (d) { return { status: r.status, data: d }; }); })
      .then(function (res) {
        if (res.status === 400) { err((res.data.lab_run_errors || []).join("; ")); return; }
        if (res.data.lab_run_status === "execution-error") {
          err("Run failed. Reference: " + res.data.lab_run_reference);
          return;
        }
        ok(res.data);
      })
      .catch(function () { err("Network error while running the experiment."); });
  }

  function runBoth(bodies, ok, err) {
    var results = [];
    var pending = bodies.length;
    bodies.forEach(function (body) {
      postRun(body, function (result) {
        results.push(result);
        if (--pending === 0) ok(results);
      }, function (e) { err(e); });
    });
  }

  function renderWithdrawalRows(result) {
    var rows = (result.outcome || {}).rows || [];
    var table = el("table", { class: "results" }, [
      el("tr", {}, [el("th", { text: "Holder" }), el("th", { class: "num", text: "Requested" }),
                    el("th", { class: "num", text: "Filled" }), el("th", { class: "num", text: "Deferred" }),
                    el("th", { class: "num", text: "Haircut" }), el("th", { text: "Status" })])
    ]);
    rows.forEach(function (r) {
      table.appendChild(el("tr", {}, [
        el("td", { text: String(r.party_id).replace("party/", "") }),
        el("td", { class: "num", text: fmt(r.requested) }),
        el("td", { class: "num", text: fmt(r.filled) }),
        el("td", { class: "num", text: fmt(r.deferred) }),
        el("td", { class: "num", text: fmt(r.haircut) }),
        el("td", { text: String(r.party_status || "").replace("party/", "") })
      ]));
    });
    return table;
  }

  function renderProRataRows(result) {
    var participants = (result.outcome || {}).participants || [];
    var table = el("table", { class: "results" }, [
      el("tr", {}, [el("th", { text: "Claimant" }), el("th", { class: "num", text: "Requested" }),
                    el("th", { class: "num", text: "Weight" }), el("th", { class: "num", text: "Cap" }),
                    el("th", { class: "num", text: "Allocated" }), el("th", { class: "num", text: "Unmet" }),
                    el("th", { class: "num", text: "Remainder" })])
    ]);
    participants.forEach(function (p) {
      var fr = p.fractional_remainder || {};
      table.appendChild(el("tr", {}, [
        el("td", { text: String(p.participant_id).replace("party/", "") }),
        el("td", { class: "num", text: fmt(p.requested) }),
        el("td", { class: "num", text: fmt(p.weight) }),
        el("td", { class: "num", text: fmt(p.effective_cap) }),
        el("td", { class: "num", text: fmt(p.allocated) }),
        el("td", { class: "num", text: fmt(p.unmet) }),
        el("td", { class: "num", text: fmtRate(fr.remainder_numerator) + "/" + fmtRate(fr.remainder_denominator) })
      ]));
    });
    return table;
  }

  function renderFindings(findings) {
    if (!findings || !findings.length) return null;
    var prf = findings.filter(function (f) { return String(f.findings_origin) === "prf"; });
    var lab = findings.filter(function (f) { return String(f.findings_origin) !== "prf"; });
    var box = el("div", {});

    function group(title, cls, items) {
      var list = el("ul", { class: "findings " + cls });
      items.forEach(function (f) {
        list.appendChild(el("li", {}, [
          badge(f.findings_status),
          el("span", { class: "f-label", text: f.findings_label }),
          el("span", { class: "f-detail", text: JSON.stringify(f.findings_detail || "") })
        ]));
      });
      box.appendChild(el("div", { class: "finding-group" }, [
        el("h4", { class: "finding-group-title " + cls, text: title }),
        list
      ]));
    }

    if (prf.length) group("PRF VERIFIED — framework findings", "group-prf", prf);
    if (lab.length) group("LAB CONSISTENCY — lab-side assertions, not PRF claim results", "group-lab", lab);
    return box;
  }

  function renderRoots(evidence) {
    if (!evidence || !evidence.roots) return null;
    var box = el("div", { class: "roots" });
    Object.keys(evidence.roots).forEach(function (k) {
      box.appendChild(el("div", { class: "root" }, [
        el("b", { text: k + "  " }),
        el("span", { text: evidence.roots[k] })
      ]));
    });
    return box;
  }

  function renderAssessment(result) {
    var a = result.assessment || {};
    var status = a.assessment_status;
    var box = el("div", {});
    if (status) {
      box.appendChild(el("div", { style: "margin:8px 0" }, [statusBadge(status)]));
      if (a.assessment_label) box.appendChild(el("p", { text: a.assessment_label }));
      if (a.assessment_ratio !== undefined && a.assessment_ratio !== null)
        box.appendChild(el("p", { class: "hint", text: "Coverage ratio: " + fmt(a.assessment_ratio) }));
    }
    return box;
  }

  function renderOutcome(result) {
    var exp = result.experiment || {};
    var slug = exp.slug;
    if (slug === "withdrawal-constrained-liquidity") {
      var o = result.outcome || {};
      var section = el("div", { class: "result-section" }, [
        el("h3", { text: "OUTCOME — " + String(o.mechanism || "").toUpperCase() }),
        el("table", { class: "results" }, [
          el("tr", {}, [el("td", { text: "Available liquidity" }), el("td", { class: "num", text: fmt(o.available) })]),
          el("tr", {}, [el("td", { text: "Total requested" }), el("td", { class: "num", text: fmt(o.total_requested) })]),
          el("tr", {}, [el("td", { text: "Total filled" }), el("td", { class: "num", text: fmt(o.total_filled) })]),
          el("tr", {}, [el("td", { text: "Total deferred" }), el("td", { class: "num", text: fmt(o.total_deferred) })]),
          el("tr", {}, [el("td", { text: "Shortfall" }), el("td", { class: "num", text: fmt(o.shortfall) })])
        ]),
        renderWithdrawalRows(result)
      ]);
      return section;
    }
    if (slug === "insolvency-after-loss") {
      var d = (result.outcome || {}).assessment_dimensions || {};
      var econ = d.economic_solvency || {};
      var acc = d.accounting || {};
      var sec = el("div", { class: "result-section" }, [
        el("h3", { text: "OUTCOME" }),
        el("table", { class: "results" }, [
          el("tr", {}, [el("td", { text: "Economic assets" }), el("td", { class: "num", text: fmt(econ.assets) })]),
          el("tr", {}, [el("td", { text: "Economic liabilities" }), el("td", { class: "num", text: fmt(econ.liabilities) })]),
          el("tr", {}, [el("td", { text: "Coverage ratio" }), el("td", { class: "num", text: fmt(econ.ratio) })]),
          el("tr", {}, [el("td", { text: "Accounting ledger" }), el("td", { text: String(acc.status || "") })])
        ])
      ]);
      return sec;
    }
    if (slug === "pro-rata-allocation") {
      var o2 = result.outcome || {};
      var s2 = el("div", { class: "result-section" }, [
        el("h3", { text: "OUTCOME" }),
        el("table", { class: "results" }, [
          el("tr", {}, [el("td", { text: "Available capacity" }), el("td", { class: "num", text: fmt(o2.available) })]),
          el("tr", {}, [el("td", { text: "Allocated total" }), el("td", { class: "num", text: fmt(o2.allocated_total) })]),
          el("tr", {}, [el("td", { text: "Unallocated residual" }), el("td", { class: "num", text: fmt(o2.unallocated_residual) })]),
          el("tr", {}, [el("td", { text: "Residual reason" }), el("td", { text: String(o2.residual_reason || "") })]),
          el("tr", {}, [el("td", { text: "Rounding policy" }), el("td", { text: String(o2.rounding_policy || "") })])
        ]),
        renderProRataRows(result)
      ]);
      return s2;
    }
    return null;
  }

  function renderResult(result) {
    app.innerHTML = "";
    app.appendChild(el("p", { class: "hint", text: "Run " + result.lab_run_id + " · " +
      (result.experiment ? result.experiment.ref : "") }));
    app.appendChild(el("h2", { text: result.experiment ? result.experiment.title : "Lab run" }));

    if (result.lab_run_status === "execution-error") {
      app.appendChild(el("div", { class: "error-box" }, [
        el("p", { text: "Run failed. Reference: " + result.lab_run_reference }),
        el("p", { class: "hint", text: "The lab runner reported: " + (result.lab_run_error && result.lab_run_error.message) })
      ]));
      return;
    }

    app.appendChild(renderOutcome(result));

    app.appendChild(el("div", { class: "result-section" }, [
      el("h3", { text: "ASSESSMENT" }),
      renderAssessment(result)
    ]));

    app.appendChild(el("div", { class: "result-section" }, [
      el("h3", { text: "ASSURANCE" }),
      renderFindings(result.findings) || el("p", { class: "hint", text: "No findings reported." })
    ]));

    app.appendChild(el("div", { class: "result-section" }, [
      el("h3", { text: "EVIDENCE" }),
      renderRoots(result.evidence) || el("p", { class: "hint", text: "No roots reported." })
    ]));

    var ex = result.execution || {};
    app.appendChild(el("div", { class: "result-section" }, [
      el("h3", { text: "EXECUTION" }),
      el("div", { class: "meta-grid" }, [
        el("span", { text: "Lab run id" }), el("span", { text: ex.lab_run_id || "—" }),
        el("span", { text: "Runner" }), el("span", { text: String(ex.runner || "") }),
        el("span", { text: "Implementation" }), el("span", { text: ex.implementation || "—" }),
        el("span", { text: "Git / source" }), el("span", { text: String(ex.git_sha || "").slice(0, 12) }),
        el("span", { text: "Started" }), el("span", { text: ex.started_at || "—" }),
        el("span", { text: "Duration" }), el("span", { text: (ex.duration_ms || 0) + " ms" }),
        el("span", { text: "Parameter root" }), el("span", { text: result.inputs_hash || "—" })
      ])
    ]));

    app.appendChild(el("div", { class: "actions" }, [
      el("a", { class: "run", href: "./#/experiment/" + (result.experiment ? result.experiment.slug : ""),
                text: "Change assumptions and rerun" })
    ]));
  }

  function renderComparison(experiment, results) {
    app.innerHTML = "";
    app.appendChild(el("p", { class: "hint", text: experiment.experiment_ref }));
    app.appendChild(el("h2", { text: experiment.experiment_title + " — mechanism comparison" }));
    app.appendChild(el("p", { text:
      "Same demand and same available liquidity; the only change is the allocation mechanism." }));
    var grid = el("div", { class: "compare" });
    results.forEach(function (r) {
      var o = r.outcome || {};
      var label = o.mechanism ? String(o.mechanism) : (r.experiment ? r.experiment.slug : "");
      grid.appendChild(el("div", { class: "compare-col" }, [
        el("h4", { text: String(label).toUpperCase() + " — " + fmt(o.total_filled) + " served of " + fmt(o.total_requested) + " requested" }),
        renderWithdrawalRows(r),
        el("div", { style: "margin-top:10px" }, [renderAssessment(r)])
      ]));
    });
    app.appendChild(grid);
    app.appendChild(el("div", { class: "actions" }, [
      el("a", { class: "run", href: "./#/experiment/" + experiment.experiment_slug, text: "Back to experiment" })
    ]));
  }

  function router() {
    var hash = location.hash || "#/";
    app.innerHTML = "";
    var m = hash.match(/^#\/experiment\/([^/]+)$/);
    if (m) renderExperiment(decodeURIComponent(m[1]));
    else renderHome();
  }

  window.addEventListener("hashchange", router);
  router();
})();
