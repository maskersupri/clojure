;   Copyright (c) Rich Hickey, Reid Draper, and contributors.
;   All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.test.check.generators
  (:refer-clojure :exclude [int vector list hash-map map keyword
                            char boolean byte bytes sequence
                            shuffle not-empty symbol namespace
                            set sorted-set uuid double let])
  (:require [#?(:clj clojure.core :cljs cljs.core) :as core
             #?@(:cljs [:include-macros true])]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            #?@(:cljs [[goog.string :as gstring]
                       [clojure.string]])))


;; Gen
;; (internal functions)
;; ---------------------------------------------------------------------------

(defrecord Generator [gen])

(defn generator?
  "Test if `x` is a generator. Generators should be treated as opaque values."
  [x]
  (instance? Generator x))

(defn- make-gen
  [generator-fn]
  (Generator. generator-fn))

(defn call-gen
  {:no-doc true}
  [{generator-fn :gen} rnd size]
  (generator-fn rnd size))

(defn gen-pure
  {:no-doc true}
  [value]
  (make-gen
    (fn [rnd size]
      value)))

(defn gen-fmap
  {:no-doc true}
  [k {h :gen}]
  (make-gen
    (fn [rnd size]
      (k (h rnd size)))))

(defn gen-bind
  {:no-doc true}
  [{h :gen} k]
  (make-gen
    (fn [rnd size]
      (core/let [[r1 r2] (random/split rnd)
            inner (h r1 size)
            {result :gen} (k inner)]
        (result r2 size)))))

(defn lazy-random-states
  "Given a random number generator, returns an infinite lazy sequence
  of random number generators."
  [rr]
  (lazy-seq
   (core/let [[r1 r2] (random/split rr)]
     (cons r1
           (lazy-random-states r2)))))

(defn- gen-tuple
  "Takes a collection of generators and returns a generator of vectors."
  [gens]
  (make-gen
   (fn [rnd size]
     (mapv #(call-gen % %2 size) gens (random/split-n rnd (count gens))))))

;; Exported generator functions
;; ---------------------------------------------------------------------------

(defn fmap
  [f gen]
  (assert (generator? gen) "Second arg to fmap must be a generator")
  (gen-fmap #(rose/fmap f %) gen))


(defn return
  "Create a generator that always returns `value`,
  and never shrinks. You can think of this as
  the `constantly` of generators."
  [value]
  (gen-pure (rose/pure value)))

(defn- bind-helper
  [k]
  (fn [rose]
    (gen-fmap rose/join
              (make-gen
                (fn [rnd size]
                  (rose/fmap #(call-gen % rnd size)
                             (rose/fmap k rose)))))))

(defn bind
  "Create a new generator that passes the result of `gen` into function
  `k`. `k` should return a new generator. This allows you to create new
  generators that depend on the value of other generators. For example,
  to create a generator which first generates a vector of integers, and
  then chooses a random element from that vector:

      (gen/bind (gen/such-that not-empty (gen/vector gen/int))
                ;; this function takes a realized vector,
                ;; and then returns a new generator which
                ;; chooses a random element from it
                gen/elements)

  "
  [generator k]
  (assert (generator? generator) "First arg to bind must be a generator")
  (gen-bind generator (bind-helper k)))

;; Helpers
;; ---------------------------------------------------------------------------

(defn make-size-range-seq
  {:no-doc true}
  [max-size]
  (cycle (range 0 max-size)))

(defn sample-seq
  "Return a sequence of realized values from `generator`."
  ([generator] (sample-seq generator 100))
  ([generator max-size]
   (core/let [r (random/make-random)
         size-seq (make-size-range-seq max-size)]
     (core/map #(rose/root (call-gen generator %1 %2))
               (lazy-random-states r)
               size-seq))))

(defn sample
  "Return a sequence of `num-samples` (default 10)
  realized values from `generator`."
  ([generator]
   (sample generator 10))
  ([generator num-samples]
   (assert (generator? generator) "First arg to sample must be a generator")
   (take num-samples (sample-seq generator))))


(defn generate
  "Returns a single sample value from the generator, using a default
  size of 30."
  {:added "0.8.0"}
  ([generator]
     (generate generator 30))
  ([generator size]
     (core/let [rng (random/make-random)]
       (rose/root (call-gen generator rng size)))))


;; Internal Helpers
;; ---------------------------------------------------------------------------

(defn- halfs
  [n]
  (take-while #(not= 0 %) (iterate #(quot % 2) n)))

(defn- shrink-int
  [integer]
  (core/map #(- integer %) (halfs integer)))

(defn- int-rose-tree
  [value]
  (rose/make-rose value (core/map int-rose-tree (shrink-int value))))

;; calc-long is factored out to support testing the surprisingly tricky double math.  Note:
;; An extreme long value does not have a precision-preserving representation as a double.
;; Be careful about changing this code unless you understand what's happening in these
;; examples:
;;
;; (= (long (- Integer/MAX_VALUE (double (- Integer/MAX_VALUE 10)))) 10)
;; (= (long (- Long/MAX_VALUE (double (- Long/MAX_VALUE 10)))) 0)

(defn- calc-long
  [factor lower upper]
  ;; these pre- and post-conditions are disabled for deployment
  #_ {:pre [(float? factor) (>= factor 0.0) (< factor 1.0)
            (integer? lower) (integer? upper) (<= lower upper)]
      :post [(integer? %)]}
  ;; Use -' on width to maintain accuracy with overflow protection.
  #?(:clj
     (core/let [width (-' upper lower -1)]
       ;; Preserve long precision if the width is in the long range.  Otherwise, we must accept
       ;; less precision because doubles don't have enough bits to preserve long equivalence at
       ;; extreme values.
       (if (< width Long/MAX_VALUE)
         (+ lower (long (Math/floor (* factor width))))
         ;; Clamp down to upper because double math.
         (min upper (long (Math/floor (+ lower (* factor width)))))))

     :cljs
     (long (Math/floor (+ lower (- (* factor (+ 1.0 upper))
                                    (* factor lower)))))))

(defn- rand-range
  [rnd lower upper]
  {:pre [(<= lower upper)]}
  (calc-long (random/rand-double rnd) lower upper))

(defn sized
  "Create a generator that depends on the size parameter.
  `sized-gen` is a function that takes an integer and returns
  a generator."
  [sized-gen]
  (make-gen
    (fn [rnd size]
      (core/let [sized-gen (sized-gen size)]
        (call-gen sized-gen rnd size)))))

;; Combinators and helpers
;; ---------------------------------------------------------------------------

(defn resize
  "Create a new generator with `size` always bound to `n`."
  [n generator]
  (assert (generator? generator) "Second arg to resize must be a generator")
  (core/let [{:keys [gen]} generator]
    (make-gen
     (fn [rnd _size]
       (gen rnd n)))))

(defn scale
  "Create a new generator that modifies the size parameter by the given function. Intended to
   support generators with sizes that need to grow at different rates compared to the normal
   linear scaling."
  {:added "0.8.0"}
  ([f generator]
    (sized (fn [n] (resize (f n) generator)))))

(defn choose
  #?(:clj
     "Create a generator that returns long integers in the range `lower` to `upper`, inclusive."

     :cljs
     "Create a generator that returns numbers in the range
     `lower` to `upper`, inclusive.")
  [lower upper]
  ;; cast to long to support doubles as arguments per TCHECK-73
  (core/let #?(:clj
          [lower (long lower)
           upper (long upper)]

          :cljs ;; does nothing, no long in cljs
          [])
    (make-gen
     (fn [rnd _size]
       (core/let [value (rand-range rnd lower upper)]
         (rose/filter
          #(and (>= % lower) (<= % upper))
          (int-rose-tree value)))))))

(defn one-of
  "Create a generator that randomly chooses a value from the list of
  provided generators. Shrinks toward choosing an earlier generator,
  as well as shrinking the value generated by the chosen generator.

  Examples:

      (one-of [gen/int gen/boolean (gen/vector gen/int)])

  "
  [generators]
  (assert (every? generator? generators)
          "Arg to one-of must be a collection of generators")
  (assert (seq generators)
          "one-of cannot be called with an empty collection")
  (bind (choose 0 (dec (count generators)))
        #(nth generators %)))

(defn- pick
  [[h & tail] n]
  (core/let [[chance gen] h]
    (if (<= n chance)
      gen
      (recur tail (- n chance)))))

(defn frequency
  "Create a generator that chooses a generator from `pairs` based on the
  provided likelihoods. The likelihood of a given generator being chosen is
  its likelihood divided by the sum of all likelihoods

  Examples:

      (gen/frequency [[5 gen/int] [3 (gen/vector gen/int)] [2 gen/boolean]])
  "
  [pairs]
  (assert (every? (fn [[x g]] (and (number? x) (generator? g)))
                  pairs)
          "Arg to frequency must be a list of [num generator] pairs")
  (assert (seq pairs)
          "frequency cannot be called with an empty collection")
  (core/let [total (apply + (core/map first pairs))]
    (gen-bind (choose 1 total)
              #(pick pairs (rose/root %)))))

(defn elements
  "Create a generator that randomly chooses an element from `coll`.

  Examples:

      (gen/elements [:foo :bar :baz])
  "
  [coll]
  (assert (seq coll) "elements cannot be called with an empty collection")
  (core/let [v (vec coll)]
    (gen-bind (choose 0 (dec (count v)))
              #(gen-pure (rose/fmap v %)))))

(defn- such-that-helper
  [max-tries pred gen tries-left rng size]
  (if (zero? tries-left)
    (throw (ex-info (str "Couldn't satisfy such-that predicate after "
                         max-tries " tries.") {}))
    (core/let [[r1 r2] (random/split rng)
          value (call-gen gen r1 size)]
      (if (pred (rose/root value))
        (rose/filter pred value)
        (recur max-tries pred gen (dec tries-left) r2 (inc size))))))

(defn such-that
  "Create a generator that generates values from `gen` that satisfy predicate
  `pred`. Care is needed to ensure there is a high chance `gen` will satisfy
  `pred`. By default, `such-that` will try 10 times to generate a value that
  satisfies the predicate. If no value passes this predicate after this number
  of iterations, a runtime exception will be throw. You can pass an optional
  third argument to change the number of times tried. Note also that each
  time such-that retries, it will increase the size parameter.

  Examples:

      ;; generate non-empty vectors of integers
      ;; (note, gen/not-empty does exactly this)
      (gen/such-that not-empty (gen/vector gen/int))
  "
  ([pred gen]
   (such-that pred gen 10))
  ([pred gen max-tries]
   (assert (generator? gen) "Second arg to such-that must be a generator")
   (make-gen
     (fn [rand-seed size]
       (such-that-helper max-tries pred gen max-tries rand-seed size)))))

(defn not-empty
  "Modifies a generator so that it doesn't generate empty collections.

  Examples:

      ;; generate a vector of booleans, but never the empty vector
      (gen/not-empty (gen/vector gen/boolean))
  "
  [gen]
  (assert (generator? gen) "Arg to not-empty must be a generator")
  (such-that core/not-empty gen))

(defn no-shrink
  "Create a new generator that is just like `gen`, except does not shrink
  at all. This can be useful when shrinking is taking a long time or is not
  applicable to the domain."
  [gen]
  (assert (generator? gen) "Arg to no-shrink must be a generator")
  (gen-bind gen
            (fn [rose]
              (gen-pure (rose/make-rose (rose/root rose) [])))))

(defn shrink-2
  "Create a new generator like `gen`, but will consider nodes for shrinking
  even if their parent passes the test (up to one additional level)."
  [gen]
  (assert (generator? gen) "Arg to shrink-2 must be a generator")
  (gen-bind gen (comp gen-pure rose/collapse)))

(def boolean
  "Generates one of `true` or `false`. Shrinks to `false`."
  (elements [false true]))

(defn tuple
  "Create a generator that returns a vector, whose elements are chosen
  from the generators in the same position. The individual elements shrink
  according to their generator, but the value will never shrink in count.

  Examples:

      (def t (tuple gen/int gen/boolean))
      (sample t)
      ;; => ([1 true] [2 true] [2 false] [1 false] [0 true] [-2 false] [-6 false]
      ;; =>  [3 true] [-4 false] [9 true]))
  "
  [& generators]
  (assert (every? generator? generators)
          "Args to tuple must be generators")
  (gen-bind (gen-tuple generators)
            (fn [roses]
              (gen-pure (rose/zip core/vector roses)))))

(def int
  "Generates a positive or negative integer bounded by the generator's
  `size` parameter.
  (Really returns a long)"
  (sized (fn [size] (choose (- size) size))))

(def nat
  "Generates natural numbers, starting at zero. Shrinks to zero."
  (fmap #(Math/abs (long %)) int))

(def pos-int
  "Generate positive integers bounded by the generator's `size` parameter."
  nat)

(def neg-int
  "Generate negative integers bounded by the generator's `size` parameter."
  (fmap #(* -1 %) nat))

(def s-pos-int
  "Generate strictly positive integers bounded by the generator's `size`
   parameter."
  (fmap inc nat))

(def s-neg-int
  "Generate strictly negative integers bounded by the generator's `size`
   parameter."
  (fmap dec neg-int))

(defn vector
  "Create a generator whose elements are chosen from `gen`. The count of the
  vector will be bounded by the `size` generator parameter."
  ([generator]
   (assert (generator? generator) "Arg to vector must be a generator")
   (gen-bind
     (sized #(choose 0 %))
     (fn [num-elements-rose]
       (gen-bind (gen-tuple (repeat (rose/root num-elements-rose)
                                    generator))
                 (fn [roses]
                   (gen-pure (rose/shrink core/vector
                                          roses)))))))
  ([generator num-elements]
   (assert (generator? generator) "First arg to vector must be a generator")
   (apply tuple (repeat num-elements generator)))
  ([generator min-elements max-elements]
   (assert (generator? generator) "First arg to vector must be a generator")
   (gen-bind
     (choose min-elements max-elements)
     (fn [num-elements-rose]
       (gen-bind (gen-tuple (repeat (rose/root num-elements-rose)
                                    generator))
                 (fn [roses]
                   (gen-bind
                     (gen-pure (rose/shrink core/vector
                                            roses))
                     (fn [rose]
                       (gen-pure (rose/filter
                                   (fn [v] (and (>= (count v) min-elements)
                                                (<= (count v) max-elements))) rose))))))))))

(defn list
  "Like `vector`, but generates lists."
  [generator]
  (assert (generator? generator) "First arg to list must be a generator")
  (gen-bind (sized #(choose 0 %))
            (fn [num-elements-rose]
              (gen-bind (gen-tuple (repeat (rose/root num-elements-rose)
                                           generator))
                        (fn [roses]
                          (gen-pure (rose/shrink core/list
                                                 roses)))))))

(defn- swap
  [coll [i1 i2]]
  (assoc coll i2 (coll i1) i1 (coll i2)))

(defn
  ^{:added "0.6.0"}
  shuffle
  "Create a generator that generates random permutations of `coll`. Shrinks
  toward the original collection: `coll`. `coll` will be turned into a vector,
  if it's not already."
  [coll]
  (core/let [index-gen (choose 0 (dec (count coll)))]
    (fmap #(reduce swap (vec coll) %)
          ;; a vector of swap instructions, with count between
          ;; zero and 2 * count. This means that the average number
          ;; of instructions is count, which should provide sufficient
          ;; (though perhaps not 'perfect') shuffling. This still gives us
          ;; nice, relatively quick shrinks.
          (vector (tuple index-gen index-gen) 0 (* 2 (count coll))))))

;; NOTE cljs: Comment out for now - David

#?(:clj
    (def byte
      "Generates `java.lang.Byte`s, using the full byte-range."
      (fmap core/byte (choose Byte/MIN_VALUE Byte/MAX_VALUE))))

#?(:clj
    (def bytes
      "Generates byte-arrays."
      (fmap core/byte-array (vector byte))))

(defn hash-map
  "Like clojure.core/hash-map, except the values are generators.
   Returns a generator that makes maps with the supplied keys and
   values generated using the supplied generators.

  Examples:

    (gen/hash-map :a gen/boolean :b gen/nat)
  "
  [& kvs]
  (assert (even? (count kvs)))
  (core/let [ks (take-nth 2 kvs)
        vs (take-nth 2 (rest kvs))]
    (assert (every? generator? vs)
            "Value args to hash-map must be generators")
    (fmap #(zipmap ks %)
          (apply tuple vs))))

;; Collections of distinct elements
;; (has to be done in a low-level way (instead of with combinators)
;;  and is subject to the same kind of failure as such-that)
;; ---------------------------------------------------------------------------

(defn ^:private transient-set-contains?
  [s k]
  #? (:clj
      (.contains ^clojure.lang.ITransientSet s k)
      :cljs
      (some? (-lookup s k))))

(defn ^:private coll-distinct-by*
  "Returns a rose tree."
  [empty-coll key-fn shuffle-fn gen rng size num-elements min-elements max-tries]
  {:pre [gen (:gen gen)]}
  (loop [rose-trees (transient [])
         s (transient #{})
         rng rng
         size size
         tries 0]
    (cond (and (= max-tries tries)
               (< (count rose-trees) min-elements))
          (throw (ex-info "Couldn't generate enough distinct elements!"
                          {:gen gen
                           :max-tries max-tries
                           :num-elements num-elements
                           :so-far (->> rose-trees
                                        (persistent!)
                                        (core/map rose/root))}))


          (or (= max-tries tries)
              (= (count rose-trees) num-elements))
          (->> (persistent! rose-trees)
               ;; we shuffle the rose trees so that we aren't biased
               ;; toward generating "smaller" elements earlier in the
               ;; collection (only applies to ordered collections)
               ;;
               ;; shuffling the rose trees is more efficient than
               ;; (bind ... shuffle) because we only perform the
               ;; shuffling once and we have no need to shrink the
               ;; shufling.
               (shuffle-fn rng)
               (rose/shrink #(into empty-coll %&)))

          :else
          (core/let [[rng1 rng2] (random/split rng)
                rose (call-gen gen rng1 size)
                root (rose/root rose)
                k (key-fn root)]
            (if (transient-set-contains? s k)
              (recur rose-trees s rng2 (inc size) (inc tries))
              (recur (conj! rose-trees rose)
                     (conj! s k)
                     rng2
                     size
                     0))))))

(defn ^:private distinct-by?
  "Like clojure.core/distinct? but takes a collection instead of varargs,
  and returns true for empty collections."
  [f coll]
  (or (empty? coll)
      (apply distinct? (core/map f coll))))

(defn ^:private the-shuffle-fn
  "Returns a shuffled version of coll according to the rng.

  Note that this is not a generator, it is just a utility function."
  [rng coll]
  (core/let [empty-coll (empty coll)
        v (vec coll)
        card (count coll)
        dec-card (dec card)]
    (into empty-coll
          (first
           (reduce (fn [[v rng] idx]
                     (core/let [[rng1 rng2] (random/split rng)
                           swap-idx (rand-range rng1 idx dec-card)]
                       [(swap v [idx swap-idx]) rng2]))
                   [v rng]
                   (range card))))))

(defn ^:private coll-distinct-by
  [empty-coll key-fn allows-dupes? ordered? gen
   {:keys [num-elements min-elements max-elements max-tries] :or {max-tries 10}}]
  (core/let [shuffle-fn (if ordered?
                     the-shuffle-fn
                     (fn [_rng coll] coll))
        hard-min-elements (or num-elements min-elements 1)]
    (if num-elements
      (core/let [size-pred #(= num-elements (count %))]
        (assert (and (nil? min-elements) (nil? max-elements)))
        (make-gen
         (fn [rng gen-size]
           (rose/filter
            (if allows-dupes?
              ;; is there a smarter way to do the shrinking than checking
              ;; the distinctness of the entire collection at each
              ;; step?
              (every-pred size-pred #(distinct-by? key-fn %))
              size-pred)
            (coll-distinct-by* empty-coll key-fn shuffle-fn gen rng gen-size
                               num-elements hard-min-elements max-tries)))))
      (core/let [min-elements (or min-elements 0)
                 size-pred (if max-elements
                             #(<= min-elements (count %) max-elements)
                             #(<= min-elements (count %)))]
        (gen-bind
         (if max-elements
           (choose min-elements max-elements)
           (sized #(choose min-elements (+ min-elements %))))
         (fn [num-elements-rose]
           (core/let [num-elements (rose/root num-elements-rose)]
             (make-gen
              (fn [rng gen-size]
                (rose/filter
                 (if allows-dupes?
                   ;; same comment as above
                   (every-pred size-pred #(distinct-by? key-fn %))
                   size-pred)
                 (coll-distinct-by* empty-coll key-fn shuffle-fn gen rng gen-size
                                    num-elements hard-min-elements max-tries)))))))))))


;; I tried to reduce the duplication in these docstrings with a macro,
;; but couldn't make it work in cljs.

(defn vector-distinct
  "Generates a vector of elements from the given generator, with the
  guarantee that the elements will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  {:added "0.9.0"}
  ([gen] (vector-distinct gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to vector-distinct must be a generator!")
   (coll-distinct-by [] identity true true gen opts)))

(defn list-distinct
  "Generates a list of elements from the given generator, with the
  guarantee that the elements will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  {:added "0.9.0"}
  ([gen] (list-distinct gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to list-distinct must be a generator!")
   (coll-distinct-by () identity true true gen opts)))

(defn vector-distinct-by
  "Generates a vector of elements from the given generator, with the
  guarantee that (map key-fn the-vector) will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  {:added "0.9.0"}
  ([key-fn gen] (vector-distinct-by key-fn gen {}))
  ([key-fn gen opts]
   (assert (generator? gen) "First arg to vector-distinct-by must be a generator!")
   (coll-distinct-by [] key-fn true true gen opts)))

(defn list-distinct-by
  "Generates a list of elements from the given generator, with the
  guarantee that (map key-fn the-list) will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  {:added "0.9.0"}
  ([key-fn gen] (list-distinct-by key-fn gen {}))
  ([key-fn gen opts]
   (assert (generator? gen) "First arg to list-distinct-by must be a generator!")
   (coll-distinct-by () key-fn true true gen opts)))

(defn set
  "Generates a set of elements from the given generator.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  {:added "0.9.0"}
  ([gen] (set gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to set must be a generator!")
   (coll-distinct-by #{} identity false false gen opts)))

(defn sorted-set
  "Generates a sorted set of elements from the given generator.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  {:added "0.9.0"}
  ([gen] (sorted-set gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to sorted-set must be a generator!")
   (coll-distinct-by (core/sorted-set) identity false false gen opts)))

(defn map
  "Create a generator that generates maps, with keys chosen from
  `key-gen` and values chosen from `val-gen`.

  If the key generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as such-that.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)"
  ([key-gen val-gen] (map key-gen val-gen {}))
  ([key-gen val-gen opts]
   (coll-distinct-by {} first false false (tuple key-gen val-gen) opts)))

;; large integers
;; ---------------------------------------------------------------------------

;; This approach has a few distribution edge cases, but is pretty good
;; for expected uses and is way better than nothing.

(def ^:private gen-raw-long
  "Generates a single uniformly random long, does not shrink."
  (make-gen (fn [rnd _size]
              (rose/pure (random/rand-long rnd)))))

(def ^:private MAX_INTEGER
  #?(:clj Long/MAX_VALUE :cljs (dec (apply * (repeat 53 2)))))
(def ^:private MIN_INTEGER
  #?(:clj Long/MIN_VALUE :cljs (- MAX_INTEGER)))

(defn ^:private abs
  [x]
  #?(:clj (Math/abs (long x)) :cljs (Math/abs x)))

(defn ^:private long->large-integer
  [bit-count x min max]
  (loop [res (-> x
                 (#?(:clj bit-shift-right :cljs .shiftRight)
                    (- 64 bit-count))
                 #?(:cljs .toNumber)
                 ;; so we don't get into an infinite loop bit-shifting
                 ;; -1
                 (cond-> (zero? min) (abs)))]
    (if (<= min res max)
      res
      (core/let [res' (- res)]
        (if (<= min res' max)
          res'
          (recur #?(:clj (bit-shift-right res 1)
                    ;; emulating bit-shift-right
                    :cljs (-> res
                              (cond-> (odd? res)
                                ((if (neg? res) inc dec)))
                              (/ 2)))))))))

(defn ^:private large-integer**
  "Like large-integer*, but assumes range includes zero."
  [min max]
  (sized (fn [size]
           (core/let [size (core/max size 1) ;; no need to worry about size=0
                 max-bit-count (core/min size #?(:clj 64 :cljs 54))]
             (gen-fmap (fn [rose]
                         (core/let [[bit-count x] (rose/root rose)]
                           (int-rose-tree (long->large-integer bit-count x min max))))
                       (tuple (choose 1 max-bit-count)
                              gen-raw-long))))))


(defn large-integer*
  "Like large-integer, but accepts options:

    :min  the minimum integer (inclusive)
    :max  the maximum integer (inclusive)

  Both :min and :max are optional."
  {:added "0.9.0"}
  [{:keys [min max]}]
  (core/let [min (or min MIN_INTEGER)
        max (or max MAX_INTEGER)]
    (assert (<= min max))
    (such-that #(<= min % max)
               (if (<= min 0 max)
                 (large-integer** min max)
                 (if (< max 0)
                   (fmap #(+ max %) (large-integer** (- min max) 0))
                   (fmap #(+ min %) (large-integer** 0 (- max min))))))))

(def ^{:added "0.9.0"} large-integer
  "Generates a platform-native integer from the full available range
  (in clj, 64-bit Longs, and in cljs, numbers between -(2^53 - 1) and
  (2^53 - 1)).

  Use large-integer* for more control."
  (large-integer* {}))


;; doubles
;; ---------------------------------------------------------------------------


;; This code is a lot more complex than any reasonable person would
;; expect, for two reasons:
;;
;; 1) I wanted the generator to start with simple values and grow with
;; the size parameter, as well as shrink back to simple values. I
;; decided to define "simple" as numbers with simpler (closer to 0)
;; exponents, with simpler fractional parts (fewer lower-level bits
;; set), and with positive being simpler than negative. I also wanted
;; to take a optional min/max parameters, which complicates the hell
;; out of things
;;
;; 2) It works in CLJS as well, which has fewer utility functions for
;; doubles, and I wanted it to work exactly the same way in CLJS just
;; to validate the whole cross-platform situation. It should generate
;; the exact same numbers on both platforms.
;;
;; Some of the lower level stuff could probably be less messy and
;; faster, especially for CLJS.

(def ^:private POS_INFINITY #?(:clj Double/POSITIVE_INFINITY, :cljs (.-POSITIVE_INFINITY js/Number)))
(def ^:private NEG_INFINITY #?(:clj Double/NEGATIVE_INFINITY, :cljs (.-NEGATIVE_INFINITY js/Number)))
(def ^:private MAX_POS_VALUE #?(:clj Double/MAX_VALUE, :cljs (.-MAX_VALUE js/Number)))
(def ^:private MIN_NEG_VALUE (- MAX_POS_VALUE))
(def ^:private NAN #?(:clj Double/NaN, :cljs (.-NaN js/Number)))

(defn ^:private uniform-integer
  "Generates an integer uniformly in the range 0..(2^bit-count-1)."
  [bit-count]
  {:assert [(<= 0 bit-count 52)]}
  (if (<= bit-count 32)
    ;; the case here is just for cljs
    (choose 0 (case (long bit-count)
                32 0xffffffff
                31 0x7fffffff
                (-> 1 (bit-shift-left bit-count) dec)))
    (fmap (fn [[upper lower]]
            #? (:clj
                (-> upper (bit-shift-left 32) (+ lower))

                :cljs
                (-> upper (* 0x100000000) (+ lower))))
          (tuple (uniform-integer (- bit-count 32))
                 (uniform-integer 32)))))

(defn ^:private scalb
  [x exp]
  #?(:clj (Math/scalb ^double x ^int exp)
     :cljs (* x (.pow js/Math 2 exp))))

(defn ^:private fifty-two-bit-reverse
  "Bit-reverses an integer in the range [0, 2^52)."
  [n]
  #? (:clj
      (-> n (Long/reverse) (unsigned-bit-shift-right 12))

      :cljs
      (loop [out 0
             n n
             out-shifter (Math/pow 2 52)]
        (if (< n 1)
          (* out out-shifter)
          (recur (-> out (* 2) (+ (bit-and n 1)))
                 (/ n 2)
                 (/ out-shifter 2))))))

(def ^:private backwards-shrinking-significand
  "Generates a 52-bit non-negative integer that shrinks toward having
  fewer lower-order bits (and shrinks to 0 if possible)."
  (fmap fifty-two-bit-reverse
        (sized (fn [size]
                 (gen-bind (choose 0 (min size 52))
                           (fn [rose]
                             (uniform-integer (rose/root rose))))))))

(defn ^:private get-exponent
  [x]
  #? (:clj
      (Math/getExponent ^Double x)

      :cljs
      (if (zero? x)
        -1023
        (core/let [x (Math/abs x)

                   res
                   (Math/floor (* (Math/log x) (.-LOG2E js/Math)))

                   t (scalb x (- res))]
          (cond (< t 1) (dec res)
                (<= 2 t) (inc res)
                :else res)))))

(defn ^:private double-exp-and-sign
  "Generates [exp sign], where exp is in [-1023, 1023] and sign is 1
  or -1. Only generates values for exp and sign for which there are
  doubles within the given bounds."
  [lower-bound upper-bound]
  (letfn [(gen-exp [lb ub]
            (sized (fn [size]
                     (core/let [qs8 (bit-shift-left 1 (quot (min 200 size) 8))]
                       (cond (<= lb 0 ub)
                             (choose (max lb (- qs8)) (min ub qs8))

                             (< ub 0)
                             (choose (max lb (- ub qs8)) ub)

                             :else
                             (choose lb (min ub (+ lb qs8))))))))]
    (if (and (nil? lower-bound)
             (nil? upper-bound))
      (tuple (gen-exp -1023 1023)
             (elements [1.0 -1.0]))
      (core/let [lower-bound (or lower-bound MIN_NEG_VALUE)
                 upper-bound (or upper-bound MAX_POS_VALUE)
                 lbexp (max -1023 (get-exponent lower-bound))
                 ubexp (max -1023 (get-exponent upper-bound))]
        (cond (<= 0.0 lower-bound)
              (tuple (gen-exp lbexp ubexp)
                     (return 1.0))

              (<= upper-bound 0.0)
              (tuple (gen-exp ubexp lbexp)
                     (return -1.0))

              :else
              (fmap (fn [[exp sign :as pair]]
                      (if (or (and (neg? sign) (< lbexp exp))
                              (and (pos? sign) (< ubexp exp)))
                        [exp (- sign)]
                        pair))
                    (tuple
                     (gen-exp -1023 (max ubexp lbexp))
                     (elements [1.0 -1.0]))))))))

(defn ^:private block-bounds
  "Returns [low high], the smallest and largest numbers in the given
  range."
  [exp sign]
  (if (neg? sign)
    (core/let [[low high] (block-bounds exp (- sign))]
      [(- high) (- low)])
    (if (= -1023 exp)
      [0.0 (-> 1.0 (scalb 52) dec (scalb -1074))]
      [(scalb 1.0 exp)
       (-> 1.0 (scalb 52) dec (scalb (- exp 51)))])))

(defn ^:private double-finite
  [ lower-bound upper-bound]
  {:pre [(or (nil? lower-bound)
             (nil? upper-bound)
             (<= lower-bound upper-bound))]}
  (core/let [pred (if lower-bound
                    (if upper-bound
                      #(<= lower-bound % upper-bound)
                      #(<= lower-bound %))
                    (if upper-bound
                      #(<= % upper-bound)))

             gen
             (fmap (fn [[[exp sign] significand]]
                     (core/let [ ;; 1.0 <= base < 2.0
                                base (inc (/ significand (Math/pow 2 52)))
                                x (-> base (scalb exp) (* sign))]
                       (if (or (nil? pred) (pred x))
                         x
                         ;; Scale things a bit when we have a partial range
                         ;; to deal with. It won't be great for generating
                         ;; simple numbers, but oh well.
                         (core/let [[low high] (block-bounds exp sign)

                                    block-lb (cond-> low  lower-bound (max lower-bound))
                                    block-ub (cond-> high upper-bound (min upper-bound))
                                    x (+ block-lb (* (- block-ub block-lb) (- base 1)))]
                           (-> x (min block-ub) (max block-lb))))))
                   (tuple (double-exp-and-sign lower-bound upper-bound)
                          backwards-shrinking-significand))]
    ;; wrapping in the such-that is necessary for staying in bounds
    ;; during shrinking
    (cond->> gen pred (such-that pred))))

(defn double*
  "Generates a 64-bit floating point number. Options:

    :infinite? - whether +/- infinity can be generated (default true)
    :NaN?      - whether NaN can be generated (default true)
    :min       - minimum value (inclusive, default none)
    :max       - maximum value (inclusive, default none)

  Note that the min/max options must be finite numbers. Supplying a
  min precludes -Infinity, and supplying a max precludes +Infinity."
  {:added "0.9.0"}
  [{:keys [infinite? NaN? min max]
    :or {infinite? true, NaN? true}}]
  (core/let [frequency-arg (cond-> [[95 (double-finite min max)]]

                             (if (nil? min)
                               (or (nil? max) (<= 0.0 max))
                               (if (nil? max)
                                 (<= min 0.0)
                                 (<= min 0.0 max)))
                             (conj
                              ;; Add zeros here as a special case, since
                              ;; the `finite` code considers zeros rather
                              ;; complex (as they have a -1023 exponent)
                              ;;
                              ;; I think most uses can't distinguish 0.0
                              ;; from -0.0, but seems worth throwing both
                              ;; in just in case.
                              [1 (return 0.0)]
                              [1 (return -0.0)])

                             (and infinite? (nil? max))
                             (conj [1 (return POS_INFINITY)])

                             (and infinite? (nil? min))
                             (conj [1 (return NEG_INFINITY)])

                             NaN? (conj [1 (return NAN)]))]
    (if (= 1 (count frequency-arg))
      (-> frequency-arg first second)
      (frequency frequency-arg))))

(def ^{:added "0.9.0"} double
  "Generates 64-bit floating point numbers from the entire range,
  including +/- infinity and NaN. Use double* for more control."
  (double* {}))


;; Characters & Strings
;; ---------------------------------------------------------------------------

(def char
  "Generates character from 0-255."
  (fmap core/char (choose 0 255)))

(def char-ascii
  "Generate only ascii character."
  (fmap core/char (choose 32 126)))

(def char-alphanumeric
  "Generate alphanumeric characters."
  (fmap core/char
        (one-of [(choose 48 57)
                 (choose 65 90)
                 (choose 97 122)])))

(def ^{:deprecated "0.6.0"}
  char-alpha-numeric
  "Deprecated - use char-alphanumeric instead.

  Generate alphanumeric characters."
  char-alphanumeric)

(def char-alpha
  "Generate alpha characters."
  (fmap core/char
        (one-of [(choose 65 90)
                 (choose 97 122)])))

(def ^{:private true} char-symbol-special
  "Generate non-alphanumeric characters that can be in a symbol."
  (elements [\* \+ \! \- \_ \?]))

(def ^{:private true} char-keyword-rest
  "Generate characters that can be the char following first of a keyword."
  (frequency [[2 char-alphanumeric]
              [1 char-symbol-special]]))

(def ^{:private true} char-keyword-first
  "Generate characters that can be the first char of a keyword."
  (frequency [[2 char-alpha]
              [1 char-symbol-special]]))

(def string
  "Generate strings. May generate unprintable characters."
  (fmap clojure.string/join (vector char)))

(def string-ascii
  "Generate ascii strings."
  (fmap clojure.string/join (vector char-ascii)))

(def string-alphanumeric
  "Generate alphanumeric strings."
  (fmap clojure.string/join (vector char-alphanumeric)))

(def ^{:deprecated "0.6.0"}
  string-alpha-numeric
  "Deprecated - use string-alphanumeric instead.

  Generate alphanumeric strings."
  string-alphanumeric)

(defn- digit?
  [d]
  #?(:clj  (Character/isDigit ^Character d)
     :cljs (gstring/isNumeric d)))

(defn- +-or---digit?
  "Returns true if c is \\+ or \\- and d is non-nil and a digit.

  Symbols that start with +3 or -2 are not readable because they look
  like numbers."
  [c d]
  (core/boolean (and d
                     (or (#?(:clj = :cljs identical?) \+ c)
                         (#?(:clj = :cljs identical?) \- c))
                     (digit? d))))

(def ^{:private true} namespace-segment
  "Generate the segment of a namespace."
  (->> (tuple char-keyword-first (vector char-keyword-rest))
       (such-that (fn [[c [d]]] (not (+-or---digit? c d))))
       (fmap (fn [[c cs]] (clojure.string/join (cons c cs))))))

(def ^{:private true} namespace
  "Generate a namespace (or nil for no namespace)."
  (->> (vector namespace-segment)
       (fmap (fn [v] (when (seq v)
                       (clojure.string/join "." v))))))

(def ^{:private true} keyword-segment-rest
  "Generate segments of a keyword (between \\:)"
  (->> (tuple char-keyword-rest (vector char-keyword-rest))
       (fmap (fn [[c cs]] (clojure.string/join (cons c cs))))))

(def ^{:private true} keyword-segment-first
  "Generate segments of a keyword that can be first (between \\:)"
  (->> (tuple char-keyword-first (vector char-keyword-rest))
       (fmap (fn [[c cs]] (clojure.string/join (cons c cs))))))

(defn ^:private resize-symbolish-generator
  "Scales the sizing down on a keyword or symbol generator so as to
  make it reasonable."
  [g]
  ;; function chosen by ad-hoc experimentation
  (scale #(long (Math/pow % 0.46)) g))

(def keyword
  "Generate keywords without namespaces."
  (->> (tuple keyword-segment-first (vector keyword-segment-rest))
       (fmap (fn [[c cs]]
               (core/keyword (clojure.string/join ":" (cons c cs)))))
       (resize-symbolish-generator)))

(def
  ^{:added "0.5.9"}
  keyword-ns
  "Generate keywords with optional namespaces."
  (->> (tuple namespace char-keyword-first (vector char-keyword-rest))
       (fmap (fn [[ns c cs]]
               (core/keyword ns (clojure.string/join (cons c cs)))))
       (resize-symbolish-generator)))

(def ^{:private true} char-symbol-first
  (frequency [[10 char-alpha]
              [5 char-symbol-special]
              [1 (return \.)]]))

(def ^{:private true} char-symbol-rest
  (frequency [[10 char-alphanumeric]
              [5 char-symbol-special]
              [1 (return \.)]]))

(def symbol
  "Generate symbols without namespaces."
  (frequency [[100 (->> (tuple char-symbol-first (vector char-symbol-rest))
                        (such-that (fn [[c [d]]] (not (+-or---digit? c d))))
                        (fmap (fn [[c cs]] (core/symbol (clojure.string/join (cons c cs)))))
                        (resize-symbolish-generator))]
              [1 (return '/)]]))

(def
  ^{:added "0.5.9"}
  symbol-ns
  "Generate symbols with optional namespaces."
  (frequency [[100 (->> (tuple namespace char-symbol-first (vector char-symbol-rest))
                        (such-that (fn [[_ c [d]]] (not (+-or---digit? c d))))
                        (fmap (fn [[ns c cs]] (core/symbol ns (clojure.string/join (cons c cs)))))
                        (resize-symbolish-generator))]
              [1 (return '/)]]))

(def ratio
  "Generates a `clojure.lang.Ratio`. Shrinks toward 0. Not all values generated
  will be ratios, as many values returned by `/` are not ratios."
  (fmap
    (fn [[a b]] (/ a b))
    (tuple int
           (such-that (complement zero?) int))))

(def ^{:added "0.9.0"} uuid
  "Generates a random type-4 UUID. Does not shrink."
  (no-shrink
   #?(:clj
      ;; this could be done with combinators, but doing it low-level
      ;; seems to be 10x faster
      (make-gen
       (fn [rng _size]
         (core/let [[r1 r2] (random/split rng)
                    x1 (-> (random/rand-long r1)
                           (bit-and -45057)
                           (bit-or 0x4000))
                    x2 (-> (random/rand-long r2)
                           (bit-or -9223372036854775808)
                           (bit-and -4611686018427387905))]
           (rose/make-rose
            (java.util.UUID. x1 x2)
            []))))

      :cljs
      ;; this could definitely be optimized so that it doesn't require
      ;; generating 31 numbers
      (fmap (fn [nibbles]
              (letfn [(hex [idx] (.toString (nibbles idx) 16))]
                (core/let [rhex (-> (nibbles 15) (bit-and 3) (+ 8) (.toString 16))]
                  (core/uuid (str (hex 0)  (hex 1)  (hex 2)  (hex 3)
                                  (hex 4)  (hex 5)  (hex 6)  (hex 7)  "-"
                                  (hex 8)  (hex 9)  (hex 10) (hex 11) "-"
                                  "4"      (hex 12) (hex 13) (hex 14) "-"
                                  rhex     (hex 16) (hex 17) (hex 18) "-"
                                  (hex 19) (hex 20) (hex 21) (hex 22)
                                  (hex 23) (hex 24) (hex 25) (hex 26)
                                  (hex 27) (hex 28) (hex 29) (hex 30))))))
            (vector (choose 0 15) 31)))))

(def simple-type
  (one-of [int large-integer double char string ratio boolean keyword
           keyword-ns symbol symbol-ns uuid]))

(def simple-type-printable
  (one-of [int large-integer double char-ascii string-ascii ratio boolean
           keyword keyword-ns symbol symbol-ns uuid]))

#?(:cljs
;; http://dev.clojure.org/jira/browse/CLJS-1594
(defn ^:private hashable?
  [x]
  (if (number? x)
    (not (or (js/isNaN x)
             (= NEG_INFINITY x)
             (= POS_INFINITY x)))
    true)))

(defn container-type
  [inner-type]
  (one-of [(vector inner-type)
           (list inner-type)
           (set #?(:clj inner-type
                   :cljs (such-that hashable? inner-type)))
           ;; scaling this by half since it naturally generates twice
           ;; as many elements
           (scale #(quot % 2)
                  (map #?(:clj inner-type
                          :cljs (such-that hashable? inner-type))
                       inner-type))]))

;; A few helpers for recursive-gen

(defn ^:private size->max-leaf-count
  [size]
  ;; chosen so that recursive-gen (with the assumptions mentioned in
  ;; the comment below) will generate structures with leaf-node-counts
  ;; not greater than the `size` ~99% of the time.
  (long (Math/pow size 1.1)))

(core/let [log2 (Math/log 2)]
  (defn ^:private random-pseudofactoring
    "Returns (not generates) a random collection of integers `xs`
  greater than 1 such that (<= (apply * xs) n)."
    [n rng]
    (if (<= n 2)
      [n]
      (core/let [log (Math/log n)
                 [r1 r2] (random/split rng)
                 n1 (-> (random/rand-double r1)
                        (* (- log log2))
                        (+ log2)
                        (Math/exp)
                        (long))
                 n2 (quot n n1)]
        (if (and (< 1 n1) (< 1 n2))
          (cons n1 (random-pseudofactoring n2 r2))
          [n])))))

(defn ^:private randomized
  "Like sized, but passes an rng instead of a size."
  [func]
  (make-gen (fn [rng size]
              (core/let [[r1 r2] (random/split rng)]
                (call-gen
                 (func r1)
                 r2
                 size)))))

(defn
  ^{:added "0.5.9"}
  recursive-gen
  "This is a helper for writing recursive (tree-shaped) generators. The first
  argument should be a function that takes a generator as an argument, and
  produces another generator that 'contains' that generator. The vector function
  in this namespace is a simple example. The second argument is a scalar
  generator, like boolean. For example, to produce a tree of booleans:

    (gen/recursive-gen gen/vector gen/boolean)

  Vectors or maps either recurring or containing booleans or integers:

    (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner)
                                                (gen/map inner inner)]))
                       (gen/one-of [gen/boolean gen/int]))

  Note that raw scalar values will be generated as well. To prevent this, you
  can wrap the returned generator with the function passed as the first arg,
  e.g.:

    (gen/vector (gen/recursive-gen gen/vector gen/boolean))"
  [container-gen-fn scalar-gen]
  (assert (generator? scalar-gen)
          "Second arg to recursive-gen must be a generator")
  ;; The trickiest part about this is sizing. The strategy here is to
  ;; assume that the container generators will (like the normal
  ;; collection generators in this namespace) have a size bounded by
  ;; the `size` parameter, and with that assumption we can give an
  ;; upper bound to the total number of leaf nodes in the generated
  ;; structure.
  ;;
  ;; So we first pick an upper bound, and pick it to be somewhat
  ;; larger than the real `size` since on average they will be rather
  ;; smaller. Then we factor that upper bound into integers to give us
  ;; the size to use at each depth, assuming that the total size
  ;; should sort of be the product of the factored sizes.
  ;;
  ;; This is all a bit weird and hard to explain precisely but I think
  ;; it works reasonably and definitely better than the old code.
  (sized (fn [size]
           (bind (choose 0 (size->max-leaf-count size))
                 (fn [max-leaf-count]
                   (randomized
                    (fn [rng]
                      (core/let [sizes (random-pseudofactoring max-leaf-count rng)
                                 sized-scalar-gen (resize size scalar-gen)]
                        (reduce (fn [g size]
                                  (bind (choose 0 10)
                                        (fn [x]
                                          (if (zero? x)
                                            sized-scalar-gen
                                            (resize size
                                                    (container-gen-fn g))))))
                                sized-scalar-gen
                                sizes)))))))))

(def any
  "A recursive generator that will generate many different, often nested, values"
  (recursive-gen container-type simple-type))

(def any-printable
  "Like any, but avoids characters that the shell will interpret as actions,
  like 7 and 14 (bell and alternate character set command)"
  (recursive-gen container-type simple-type-printable))


;; Macros
;; ---------------------------------------------------------------------------

(defmacro let
  "Macro for building generators using values from other generators.
  Uses a binding vector with the same syntax as clojure.core/let,
  where the right-hand side of the binding pairs are generators, and
  the left-hand side are names (or destructuring forms) for generated
  values.

  Subsequent generator expressions can refer to the previously bound
  values, in the same way as clojure.core/let.

  The body of the let can be either a value or a generator, and does
  the expected thing in either case. In this way let provides the
  functionality of both `bind` and `fmap`.

  Examples:

    (gen/let [strs (gen/not-empty (gen/list gen/string))
              s (gen/elements strs)]
      {:some-strings strs
       :one-of-those-strings s})

    ;; generates collections of \"users\" that have integer IDs
    ;; from 0...N-1, but are in a random order
    (gen/let [users (gen/list (gen/hash-map :name gen/string-ascii
                                            :age gen/nat))]
      (->> users
           (map #(assoc %2 :id %1) (range))
           (gen/shuffle)))"
  {:added "0.9.0"}
  [bindings & body]
  (assert (vector? bindings)
          "First arg to gen/let must be a vector of bindings.")
  (assert (even? (count bindings))
          "gen/let requires an even number of forms in binding vector")
  (if (empty? bindings)
    `(core/let [val# (do ~@body)]
       (if (generator? val#)
         val#
         (return val#)))
    (core/let [[binding gen & more] bindings]
      `(bind ~gen (fn [~binding] (let [~@more] ~@body))))))
