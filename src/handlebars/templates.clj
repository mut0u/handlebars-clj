(ns handlebars.templates
  "Handlebar templating library for Clojure, allows for server or client-side
   interpretation"
  (:use [hiccup.core]
	[clojure.walk])
  (:require [clojure.string :as str]))


;; -------------------------------
;; Keep a global map of templates
;; -------------------------------

(defonce templates (atom nil))

(defn update-template [name fn]
  (assert (string? name))
  (swap! templates assoc name fn))

(defn get-template [name]
  (@templates name))

(defn all-templates []
  @templates)

(defmacro deftemplate [name & body]
  `(let [template# (list ~@body)]
     (defn ~name
       ([context#]
          (if (= context# :raw)
            template#
            (apply-template template# context#)))
       ([]  (apply-template template#)))
     (update-template (name '~name) ~name)
     ~name))

;; -------------------------------
;; Hiccup Shorthand and Helpers
;; -------------------------------

(defmacro %
  "Support {{ctx-expr}} as (% ctx.child)"
  [& exprs]
  (assert (= (count exprs) 1))
  `(quote [%var ~@exprs]))

(defn- var-expr? [expr]
  (and (sequential? expr)
       (not (sequential? (first expr)))
       (= (name (first expr)) (name '%var))))


(defmacro %h
  "Support {{#helper ctx-expr}} as (%h helper expr & body) for
   use by shorthand macros" 
  [& exprs]
  (assert (>= (count exprs) 3))
  `['%block '~(first exprs) '~(second exprs) ~@(drop 2 exprs)])

(defn- block-expr? [expr]
  (and (sequential? expr)
       (not (sequential? (first expr)))
       (= (name (first expr)) (name '%block))))

(defmacro %str
  "Special form for guaranteeing whitespace between forms on rendering"
  [& exprs]
  `['%str ~@exprs])

(defn- str-expr? [expr]
  (and (sequential? expr)
       (not (sequential? (first expr)))
       (= (name (first expr)) (name '%str))))

(defmacro %strcat
  "Special form for guaranteeing whitespace between forms on rendering"
  [& exprs]
  `['%strcat ~@exprs])

(defn- strcat-expr? [expr]
  (and (sequential? expr)
       (not (sequential? (first expr)))
       (= (name (first expr)) (name '%strcat))))

(defmacro %code
  "Support {{{ctx-expr}}} as (%code ctx.child)"
  [& exprs]
  (assert (= (count exprs) 1))
  `(quote [%code ~@exprs]))

(defn- code-expr? [expr]
  (and (sequential? expr)
       (not (sequential? (first expr)))
       (= (name (first expr)) (name '%code))))

(defmacro defhelper
  "Makes it easy to define your own block helper shorthand.  The tag
   names a %tag macro for integration with hiccup expressions,
   and a helper function %helper-tag that takes three arguments,
   the keyword symbol tag, the current context and a body function
   which you can call to evaluate the body expression of the block"
  [tag description arglist & body]
  (assert (not (= (first (str tag)) \%)))
  (let [shorthand (symbol (str "%" tag))
	longhand (symbol (str "%helper-" tag))]
    `(do (defmacro ~shorthand
	   ~description
	   [& exprs#]
	   `(%h ~'~tag ~@exprs#))
	 (defn ~longhand ~arglist
	   ~@body))))

(defn- get-helper
  [helper]
  (assert (or (fn? helper) (symbol? helper) (keyword? helper) (string? helper)))
  (if (fn? helper) helper
      (resolve (symbol (str "%helper-" helper)))))

(defn- call-helper
  [helper var fn]
  (assert (or (fn? fn) (symbol? fn)))
  ((get-helper helper) var fn))

;; --------------------------------
;; Applying templates to contexts
;; --------------------------------

(def ^:dynamic *parent-context* nil)
(def ^:dynamic *context* nil)


;; ## Expressions

(defn- hb-tag?
  "Is this the handlebar template tag?"
  [sym]
  (and (or (symbol? sym) (keyword? sym) (string? sym))
       (= (first (str sym)) \%)))

(defn- hb-expr?
  "Is this hiccup expression a handlebar template expression?"
  [expr]
  (or (var-expr? expr) (code-expr? expr) (block-expr? expr) (str-expr? expr)))


;; ## Variables

(defn- parent-ref?
  "Does this variable have a parent reference?"
  [var]
  (let [s (str var)]
    (and (>= (count s) 3)
         (= (subs s 0 3) "../"))))

(defn- var-path
  "Resolve the path reference to a path, as in get-in,
   ignoring the parent context reference if it exists."
  [var]
  (if (parent-ref? var)
    (var-path (subs (str var) 3))
    (map keyword (str/split (str var) #"\."))))

(defn- resolve-var
  "[%var <var>] => hiccup expression or string"
  [var]
  (if (= (str var) "this")
    *context*
    (let [path (var-path var)]
      (if (parent-ref? var)
	(get-in *parent-context* path)
	(get-in *context* path)))))

;; ## Expansion handlebars.clj -> hiccup

(declare resolve-template)

(defn- resolve-block
  "[%block <helper> <var> & <body>] => hiccup expression or string
   Take the block expression and pass the helper a closure that
   will expand the body in a possibly modified context"
  [[tag helper var & body]]
  (call-helper helper var
	       (fn [ctx]
		  (resolve-template body ctx))))
  
(defn- resolve-hb-expr [expr]
  (cond
   (or (var-expr? expr) (code-expr? expr))
   (resolve-var (second expr))

   (block-expr? expr)
   (resolve-block expr)

   (str-expr? expr)
   (mapcat (fn [se]
	     (list se " "))
	   (drop 1 expr))
    
   (strcat-expr? expr)
   (apply concat expr)

   (fn? expr)
   (expr :raw)
   
   true expr))

(defn- resolve-template
  "Expand a template according to the provided context"
  [template context]
  (binding [*context* context]
    (clojure.walk/prewalk resolve-hb-expr template)))

;;------------------------------------------------------
;; Render Handlebar Template as valid Hiccup Expression
;;------------------------------------------------------

(declare render-template*)

(defn hiccup-expr? [expr]
  (and (vector? expr)
       (keyword? (first expr))
       (or (not (map? (second expr))) (>= (count expr) 2))))

(defmacro with-hiccup-expr 
  "Hiccup has expressions of the form [:div [options} body ...],
   bind key elements inside the body"
  [[t o e] temp & body]
  `(let [[t# o# & b#] ~temp
	 ~t t#
	 ~o (when (map? o#) o#)
	 ~e (if ~o b# (cons o# b#))]
     ~@body))

(defn- render-options [map]
  (zipmap (keys map)
	  (mapcat render-template* (vals map))))

;; NOTE: abstract into multi method dispatch?
(defn- render-template*
  "Expand a template into a valid hiccup expression with
   handlebar expressions embedded inside"
  [expr]
  (cond
   (list? expr) ;; for having multiple top level forms in a template
   (mapcat render-template* expr)

   (hiccup-expr? expr)
   (with-hiccup-expr [tag opts exprs] expr
     (list (vec (concat (if opts (list tag (render-options opts)) (list tag))
                        (mapcat render-template* exprs)))))
   
   (var-expr? expr)
   (list (str "{{" (second expr) "}}"))

   (code-expr? expr)
   (list (str "{{{" (second expr) "}}}"))
   
   (block-expr? expr)
   (concat (list (str "{{#" (second expr) " " (nth expr 2) "}}"))
	   (mapcat render-template* (drop 3 expr))
	   (list (str "{{/" (second expr) "}}")))

   (str-expr? expr)
   (interleave
    (mapcat render-template* (rest expr))
    (repeat (- (count expr) 1) " "))

   (strcat-expr? expr)
   (mapcat render-template* (rest expr))

   true
   (list expr)))
				     
(defn- render-template [template]
  (render-template* template))
  
;; ===================
;; Built-in Helpers
;; ===================

(defhelper with
  "{{#with person}}...{{/with}} => (%with person & body)"
  [var fn]
  (fn (resolve-var var)))
  
(defhelper each
  "{{#each person}}...{{/each}} => (%each person & body)"
  [var fn]
  (map fn (resolve-var var)))

(defhelper if
  "Not an easy translation, for now translate:
   {{#if person}}...{{else}}...{{/if}} =>
   as
   (%if person & body) (%unless person & else) in code"
  [var fn]
  (when (resolve-var var)
    (fn *context*)))

(defhelper unless
  "{{#unless person}}{{/unless}} => (%unless person & body)"
   [var fn]
   (let [val (resolve-var var)]
     (when (not val)
       (fn *context*))))

(defhelper else
  "Synonym for %unless"
   [var fn]
   (let [val (resolve-var var)]
     (when (not val)
       (fn *context*))))

;; ================
;; Handlebar API
;; ================

(defn- raw-template [temp]
  (cond
   (string? temp) ((resolve (symbol temp)) :raw)
   (symbol? temp) ((resolve temp) :raw)
   (fn? temp) (temp :raw)
   true temp))

(defn apply-template
  "Render a handlebar structure as HTML, using the context to expand
  the template or render it with handlebar strings suitable for a
  client template library if no context provided."
  ([template context]
     (binding [*parent-context* context]
       (resolve-template (raw-template template) context)))
  ([template]
     (render-template (raw-template template))))

(defn inline-template
  "Inject a template as a script element into a larger hiccup expression"
  ([name template context type]
     [:script {:type (if (= type :default)
                       "text/javascript"
                       type)
               :id name}
      (if context
        (apply-template template context)
        (apply-template template))])
  ([name template context-or-type]
     (if (map? context-or-type)
       (inline-template name template context-or-type :default)
       (inline-template name template nil context-or-type)))
  ([name template]
     (inline-template name template nil :default)))

(defn html-template
  "Return the template as an HTML string"
  [template]
  (html (apply-template template)))

