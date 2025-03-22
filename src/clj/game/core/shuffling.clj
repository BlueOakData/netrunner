(ns game.core.shuffling
  (:require
   [clojure.string :as str]
   [game.core.card :refer [corp? in-discard? get-card]]
   [game.core.eid :refer [effect-completed]]
   [game.core.engine :refer [trigger-event]]
   [game.core.flags :refer [zone-locked?]]
   [game.core.moving :refer [move move-zone]]
   [game.core.say :refer [system-msg]]
   [game.core.servers :refer [name-zone]]
   [game.macros :refer [continue-ability msg req]]
   [game.utils :refer [enumerate-str quantify]]))

(defn shuffle!
  "Shuffles the vector in @state [side kw]."
  [state side kw]
  (when (contains? #{:deck :hand :discard} kw)
    (trigger-event state side (when (= :deck kw) (if (= :corp side) :corp-shuffle-deck :runner-shuffle-deck)))
    (when (and (:access @state)
               (:run @state)
               (= :corp side)
               (= :deck kw))
      (swap! state assoc-in [:run :shuffled-during-access :rd] true))
    (swap! state update-in [:stats side :shuffle-count] (fnil + 0) 1)
    (swap! state update-in [side kw] shuffle)))

(defn shuffle-cards-into-deck!
  "Shuffles a given set of cards into the deck. Will print out what's happened. Will always shuffle."
  ([state from-side card targets] (shuffle-cards-into-deck! state from-side card from-side targets))
  ([state from-side card shuffle-side targets]
   (let [targets (set (keep #(get-card state %) (flatten targets)))
         targets (filter #(if (= (:zone %) [:discard]) (not (zone-locked? state shuffle-side :discard)) true) targets)
         lhs (str " uses " (:title card)
                  (when-not (= from-side shuffle-side)
                    (str " to force the " (str/capitalize (name shuffle-side))))
                  " to shuffle ")
         rhs (if (= shuffle-side :corp) "Archives" "the Stack")]
     (if (seq targets)
       (let [cards-by-zone (group-by #(select-keys % [:side :zone]) (flatten targets))
             strs (enumerate-str (map #(str (enumerate-str (map :title (get cards-by-zone %)))
                                            " from " (name-zone (:side %) (:zone %)))
                                      (keys cards-by-zone)))]
         (doseq [t targets]
           (when-not (= (:zone t) [:deck])
             (move state shuffle-side t :deck)))
         (system-msg state from-side (str lhs strs " into " rhs))
         (shuffle! state shuffle-side :deck))
       (do
         (system-msg state from-side (str lhs rhs))
         (shuffle! state shuffle-side :deck))))))

(defn shuffle-into-deck
  [state side & args]
  (doseq [zone (filter keyword? args)]
    (move-zone state side zone :deck))
  (shuffle! state side :deck))

(defn shuffle-into-rd-effect
  ([state side eid card n] (shuffle-into-rd-effect state side eid card n false))
  ([state side eid card n all?]
   (continue-ability
     state side
     {:show-discard  true
      :choices {:max (min (-> @state :corp :discard count) n)
                :card #(and (corp? %)
                            (in-discard? %))
                :all all?}
      :msg (msg "shuffle "
                (let [seen (filter :seen targets)
                      m (count (filter #(not (:seen %)) targets))]
                  (str (enumerate-str (map :title seen))
                       (when (pos? m)
                         (str (when-not (empty? seen) " and ")
                              (quantify m "unseen card")))))
                " into R&D")
      :waiting-prompt true
      :effect (req (doseq [c targets]
                     (move state side c :deck))
                   (shuffle! state side :deck))
      :cancel-effect (req
                      (system-msg state side (str " uses " (:title card) " to shuffle R&D"))
                      (shuffle! state side :deck)
                      (effect-completed state side eid))}
     card nil)))

(defn shuffle-deck
  "Shuffle R&D/Stack."
  [state side {:keys [close]}]
  (swap! state update-in [side :deck] shuffle)
  (if close
    (do
      (swap! state update-in [side] dissoc :view-deck)
      (system-msg state side "stops looking at [pronoun] deck and shuffles it"))
    (system-msg state side "shuffles [pronoun] deck")))
