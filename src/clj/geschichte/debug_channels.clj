;;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  geschichte.debug-channels
  (:require [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.dispatch :as dispatch]
            [clojure.core.async.impl.mutex :as mutex]
            [clojure.core.async :refer [buffer]])
  (:import [java.util LinkedList Queue Iterator]
           [java.util.concurrent.locks Lock]))

(set! *warn-on-reflection* true)


(defmacro assert-unlock [lock test msg]
  `(when-not ~test
     (.unlock ~lock)
     (throw (new AssertionError (str "Assert failed: " ~msg "\n" (pr-str '~test))))))

(defn box [val]
  (reify clojure.lang.IDeref
         (deref [_] val)))

(defprotocol MMC
  (cleanup [_]))

(deftype LogManyToManyChannel [log path ^LinkedList takes ^LinkedList puts ^Queue buf closed ^Lock mutex]
  MMC
  (cleanup
    [_]
    (when-not (.isEmpty takes)
      (let [iter (.iterator takes)]
        (loop [taker (.next iter)]
          (when-not (impl/active? taker)
            (.remove iter))
          (when (.hasNext iter)
            (recur (.next iter))))))
    (when-not (.isEmpty puts)
      (let [iter (.iterator puts)]
        (loop [[putter] (.next iter)]
          (when-not (impl/active? putter)
            (.remove iter))
          (when (.hasNext iter)
            (recur (.next iter)))))))

  impl/WritePort
  (put!
    [this val handler]
    (when (nil? val)
      (throw (IllegalArgumentException. "Can't put nil on channel")))
    (.lock mutex)
    (cleanup this)
    (if @closed
      (do (.unlock mutex)
          (box false))
      (let [^Lock handler handler
            iter (.iterator takes)
            [put-cb take-cb] (when (.hasNext iter)
                               (loop [^Lock taker (.next iter)]
                                 (if (< (impl/lock-id handler) (impl/lock-id taker))
                                   (do (.lock handler) (.lock taker))
                                   (do (.lock taker) (.lock handler)))
                                 (let [ret (when (and (impl/active? handler) (impl/active? taker))
                                             [(impl/commit handler) (impl/commit taker)])]
                                   (.unlock handler)
                                   (.unlock taker)
                                   (if ret
                                     (do
                                       (.remove iter)
                                       ret)
                                     (when (.hasNext iter)
                                       (recur (.next iter)))))))]
        (if (and put-cb take-cb)
          (do
            (.unlock mutex)
            (dispatch/run (fn [] (take-cb val)))
            (swap! log update-in path (fnil conj []) val)
            (box true))
          (if (and buf (not (impl/full? buf)))
            (do
              (.lock handler)
              (let [put-cb (and (impl/active? handler) (impl/commit handler))]
                (.unlock handler)
                (swap! log update-in path (fnil conj []) val)
                (if put-cb
                  (do (impl/add! buf val)
                      (.unlock mutex)
                      (box true))
                  (do (.unlock mutex)
                      nil))))
            (do
              (when (impl/active? handler)
                (assert-unlock mutex
                               (< (.size puts) impl/MAX-QUEUE-SIZE)
                               (str "No more than " impl/MAX-QUEUE-SIZE
                                    " pending puts are allowed on a single channel."
                                    " Consider using a windowed buffer."))
                (.add puts [handler val]))
              (.unlock mutex)
              nil))))))

  impl/ReadPort
  (take!
    [this handler]
    (.lock mutex)
    (cleanup this)
    (let [^Lock handler handler
          commit-handler (fn []
                           (.lock handler)
                           (let [take-cb (and (impl/active? handler) (impl/commit handler))]
                             (.unlock handler)
                             take-cb))]
      (if (and buf (pos? (count buf)))
        (do
          (if-let [take-cb (commit-handler)]
            (let [val (impl/remove! buf)
                  iter (.iterator puts)
                  cb (when (.hasNext iter)
                       (loop [[^Lock putter val] (.next iter)]
                         (.lock putter)
                         (let [cb (and (impl/active? putter) (impl/commit putter))]
                           (.unlock putter)
                           (.remove iter)
                           (if cb
                             (do (impl/add! buf val)
                                 cb)
                             (when (.hasNext iter)
                               (recur (.next iter)))))))]
              (.unlock mutex)
              (when cb
                (dispatch/run #(cb true)))
              (box val))
            (do (.unlock mutex)
                nil)))
        (let [iter (.iterator puts)
              [take-cb put-cb val]
              (when (.hasNext iter)
                (loop [[^Lock putter val] (.next iter)]
                  (if (< (impl/lock-id handler) (impl/lock-id putter))
                    (do (.lock handler) (.lock putter))
                    (do (.lock putter) (.lock handler)))
                  (let [ret (when (and (impl/active? handler) (impl/active? putter))
                              [(impl/commit handler) (impl/commit putter) val])]
                    (.unlock handler)
                    (.unlock putter)
                    (if ret
                      (do
                        (.remove iter)
                        ret)
                      (when-not (impl/active? putter)
                        (.remove iter)
                        (when (.hasNext iter)
                          (recur (.next iter))))))))]
          (if (and put-cb take-cb)
            (do
              (.unlock mutex)
              (dispatch/run #(put-cb true))
              (box val))
            (if @closed
              (do
                (.unlock mutex)
                (if-let [take-cb (commit-handler)]
                  (box nil)
                  nil))
              (do
                (assert-unlock mutex
                               (< (.size takes) impl/MAX-QUEUE-SIZE)
                               (str "No more than " impl/MAX-QUEUE-SIZE
                                    " pending takes are allowed on a single channel."))
                (.add takes handler)
                (.unlock mutex)
                nil)))))))

  impl/Channel
  (close!
    [this]
    (.lock mutex)
    (cleanup this)
    (if @closed
      (do
        (.unlock mutex)
        nil)
      (do
        (reset! closed true)
        (let [iter (.iterator takes)]
          (when (.hasNext iter)
            (loop [^Lock taker (.next iter)]
              (.lock taker)
              (let [take-cb (and (impl/active? taker) (impl/commit taker))]
                (.unlock taker)
                (when take-cb
                  (dispatch/run (fn [] (take-cb nil))))
                (when (.hasNext iter)
                  (recur (.next iter)))))))
        (.unlock mutex)
        nil))))

#_(defn chan [log path buf]
 (LogManyToManyChannel. log path (LinkedList.) (LinkedList.) buf (atom false) (mutex/mutex)))


(defn chan
  ([log path] (chan log path nil))
  ([log path buf-or-n]
     (LogManyToManyChannel. log path
                            (LinkedList.) (LinkedList.)
                            (if (number? buf-or-n) (buffer buf-or-n) buf-or-n)
                            (atom false)
                            (mutex/mutex))))
