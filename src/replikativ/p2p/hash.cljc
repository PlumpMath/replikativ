(ns replikativ.p2p.hash
  "Hash checksumming middleware for replikativ."
  (:require [replikativ.platform-log :refer [debug info warn error]]
            [replikativ.environ :refer [*id-fn*]]
            [replikativ.crdt.materialize :refer [pub->crdt]]
            [replikativ.protocols :refer [-commit-value]]
            [clojure.set :as set]
            [full.async :refer [go-try go-loop-try <?]]
            #?(:clj [clojure.core.async :as async
                      :refer [>! timeout chan put! pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                             :refer [>! timeout chan put! pub sub unsub close!]])))

(defn- check-hash [fetched-ch new-in]
  (go-loop-try [{:keys [values peer] :as f} (<? fetched-ch)]
               (when f
                 (doseq [[id val] values]
                   (let [val (if (and (:crdt val)
                                      (:version val)
                                      (:transactions val)) ;; TODO assume commit
                               (let [crdt (<? (pub->crdt (:crdt val)))]
                                 (-commit-value crdt val))
                               val)]
                     (when (not= id (*id-fn* val))
                       (let [msg (str "CRITICAL: Fetched edn ID: "  id
                                      " does not match HASH "  (*id-fn* val)
                                      " for value " (pr-str val)
                                      " from " peer)]
                         (error msg)
                         #?(:clj (throw (IllegalStateException. msg))
                                 :cljs (throw msg))))))
                 (>! new-in f)
                 (recur (<? fetched-ch)))))

(defn- check-binary-hash [binary-out binary-fetched out new-in]
  (go-loop-try [{:keys [blob-id] :as bo} (<? binary-out)]
               (>! out bo)
               (let [{:keys [peer value] :as blob} (<? binary-fetched)
                     val-id (*id-fn* value)]
                 (when (not= val-id blob-id)
                   (let [msg (str "CRITICAL: Fetched binary ID: " blob-id
                                  " does not match HASH " val-id
                                  " for value " (take 20 (map byte value))
                                  " from " peer)]
                     (error msg)
                     #?(:clj (throw (IllegalStateException. msg))
                        :cljs (throw msg))))
                 (>! new-in blob))
               (recur (<? binary-out))))

(defn- hash-dispatch [{:keys [type]}]
  (case type
    :fetch/edn-ack :fetch/edn-ack
    :fetch/binary-ack :fetch/binary-ack
    :unrelated))

(defn- hash-out-dispatch [{:keys [type]}]
  (case type
    :fetch/binary :fetch/binary
    :unrelated))


(defn ensure-hash
  "Ensures correct uuid hashes of incoming data (commits and transactions)."
  [[in out]]
  (let [new-in (chan)
        new-out (chan)
        p-out (pub new-out hash-out-dispatch)
        p-in (pub in hash-dispatch)
        fetched-ch (chan)
        binary-out (chan)
        binary-fetched (chan)]
    (sub p-in :fetch/edn-ack fetched-ch)
    (check-hash fetched-ch new-in)

    (sub p-in :fetch/binary-ack binary-fetched)
    (sub p-out :fetch/binary binary-out)
    (check-binary-hash binary-out binary-fetched out new-in)

    (sub p-in :unrelated new-in)
    (sub p-out :unrelated out)
    [new-in new-out]))


(def datomic-hash [{:db/id {:idx -1000039, :part :db.part/db}, :db/ident :neuron/cm, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Capacitance of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000040, :part :db.part/db}, :db/ident :neuron/tau_m, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Membrane constant of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000041, :part :db.part/db}, :db/ident :neuron/e_rev_E, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Exc. reverse potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000042, :part :db.part/db}, :db/ident :neuron/e_rev_I, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Inh. reverse potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000043, :part :db.part/db}, :db/ident :neuron/v_thresh, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Threshold potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000044, :part :db.part/db}, :db/ident :neuron/tau_syn_E, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Exc. time constant of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000045, :part :db.part/db}, :db/ident :neuron/tau_syn_I, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Inh. time constant of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000046, :part :db.part/db}, :db/ident :neuron/v_reset, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Reset potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000047, :part :db.part/db}, :db/ident :neuron/v_rest, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Resting potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000048, :part :db.part/db}, :db/ident :neuron/tau_refrac, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Refractory time constant the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000049, :part :db.part/db}, :db/ident :neuron/i_offset, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Offset current of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000050, :part :db.part/db}, :db/ident :calib/alpha, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Alpha slope for sigmoid fit.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000051, :part :db.part/db}, :db/ident :calib/v-p05, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Offset for sigmoid fit.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000052, :part :db.part/db}, :db/ident :sampling/count, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000053, :part :db.part/db}, :db/ident :sampling/seed, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:part :db.part/db, :idx -1000054}, :db/ident :val/id, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000055, :part :db.part/db}, :db/ident :ref/rbm-weights, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000056, :part :db.part/db}, :db/ident :ref/rbm-v-bias, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000057, :part :db.part/db}, :db/ident :ref/rbm-h-bias, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000058, :part :db.part/db}, :db/ident :ref/neuron-params, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000059, :part :db.part/db}, :db/ident :ref/training-params, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000060, :part :db.part/db}, :db/ident :ref/trans-params, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000061, :part :db.part/db}, :db/ident :git/commit-id, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000062, :part :db.part/db}, :db/ident :topic, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000063, :part :db.part/db}, :db/ident :base-directory, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000064, :part :db.part/db}, :db/ident :ref/data, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000065, :part :db.part/db}, :db/ident :ref/spike-trains, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000066, :part :db.part/db}, :db/ident :data/name, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000067, :part :db.part/db}, :db/ident :train/epochs, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db/doc "Times to iterate over the training data.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000068, :part :db.part/db}, :db/ident :train/dt, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Time resolution for simulator.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000069, :part :db.part/db}, :db/ident :train/sim_setup_kwargs, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000070, :part :db.part/db}, :db/ident :train/burn_in_time, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000071, :part :db.part/db}, :db/ident :train/phase_duration, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Phase duration in simulator.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000072, :part :db.part/db}, :db/ident :train/learning_rate, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Starting learning rate.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000073, :part :db.part/db}, :db/ident :train/weight_recording_interval, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000074, :part :db.part/db}, :db/ident :train/stdp_burnin, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "STDP burnin pauses.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000075, :part :db.part/db}, :db/ident :train/sampling_time, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000076, :part :db.part/db}, :db/ident :train/h_count, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000077, :part :db.part/db}, :db/ident :lif/spike-trains, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db}])

(comment

  (*id-fn* [{:db/id {:idx -1000039, :part :db.part/db}, :db/ident :neuron/cm, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Capacitance of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000040, :part :db.part/db}, :db/ident :neuron/tau_m, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Membrane constant of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000041, :part :db.part/db}, :db/ident :neuron/e_rev_E, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Exc. reverse potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000042, :part :db.part/db}, :db/ident :neuron/e_rev_I, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Inh. reverse potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000043, :part :db.part/db}, :db/ident :neuron/v_thresh, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Threshold potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000044, :part :db.part/db}, :db/ident :neuron/tau_syn_E, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Exc. time constant of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000045, :part :db.part/db}, :db/ident :neuron/tau_syn_I, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Inh. time constant of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000046, :part :db.part/db}, :db/ident :neuron/v_reset, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Reset potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000047, :part :db.part/db}, :db/ident :neuron/v_rest, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Resting potential of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000048, :part :db.part/db}, :db/ident :neuron/tau_refrac, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Refractory time constant the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000049, :part :db.part/db}, :db/ident :neuron/i_offset, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Offset current of the neuron.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000050, :part :db.part/db}, :db/ident :calib/alpha, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Alpha slope for sigmoid fit.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000051, :part :db.part/db}, :db/ident :calib/v-p05, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Offset for sigmoid fit.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000052, :part :db.part/db}, :db/ident :sampling/count, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000053, :part :db.part/db}, :db/ident :sampling/seed, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:part :db.part/db, :idx -1000054}, :db/ident :val/id, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000055, :part :db.part/db}, :db/ident :ref/rbm-weights, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000056, :part :db.part/db}, :db/ident :ref/rbm-v-bias, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000057, :part :db.part/db}, :db/ident :ref/rbm-h-bias, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000058, :part :db.part/db}, :db/ident :ref/neuron-params, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000059, :part :db.part/db}, :db/ident :ref/training-params, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000060, :part :db.part/db}, :db/ident :ref/trans-params, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000061, :part :db.part/db}, :db/ident :git/commit-id, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000062, :part :db.part/db}, :db/ident :topic, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000063, :part :db.part/db}, :db/ident :base-directory, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000064, :part :db.part/db}, :db/ident :ref/data, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000065, :part :db.part/db}, :db/ident :ref/spike-trains, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000066, :part :db.part/db}, :db/ident :data/name, :db/valueType :db.type/string, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000067, :part :db.part/db}, :db/ident :train/epochs, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db/doc "Times to iterate over the training data.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000068, :part :db.part/db}, :db/ident :train/dt, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Time resolution for simulator.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000069, :part :db.part/db}, :db/ident :train/sim_setup_kwargs, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000070, :part :db.part/db}, :db/ident :train/burn_in_time, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000071, :part :db.part/db}, :db/ident :train/phase_duration, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Phase duration in simulator.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000072, :part :db.part/db}, :db/ident :train/learning_rate, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "Starting learning rate.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000073, :part :db.part/db}, :db/ident :train/weight_recording_interval, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000074, :part :db.part/db}, :db/ident :train/stdp_burnin, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db/doc "STDP burnin pauses.", :db.install/_attribute :db.part/db} {:db/id {:idx -1000075, :part :db.part/db}, :db/ident :train/sampling_time, :db/valueType :db.type/float, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000076, :part :db.part/db}, :db/ident :train/h_count, :db/valueType :db.type/long, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db} {:db/id {:idx -1000077, :part :db.part/db}, :db/ident :lif/spike-trains, :db/valueType :db.type/uuid, :db/cardinality :db.cardinality/one, :db.install/_attribute :db.part/db}]) ;; should be #uuid 3089a10e-b579-58a6-9520-afcd9d3622d6
  )
