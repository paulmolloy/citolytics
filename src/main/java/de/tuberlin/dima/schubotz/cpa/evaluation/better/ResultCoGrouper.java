package de.tuberlin.dima.schubotz.cpa.evaluation.better;

import de.tuberlin.dima.schubotz.cpa.evaluation.types.WikiSimComparableResult;
import de.tuberlin.dima.schubotz.cpa.evaluation.types.WikiSimComparableResultList;
import de.tuberlin.dima.schubotz.cpa.evaluation.utils.EvaluationMeasures;
import org.apache.flink.api.common.functions.CoGroupFunction;
import org.apache.flink.api.java.tuple.Tuple11;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.shaded.com.google.common.collect.Ordering;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ResultCoGrouper implements CoGroupFunction<
        Tuple2<String, ArrayList<String>>,
        Tuple2<String, WikiSimComparableResultList<Double>>,
        Tuple11<String, ArrayList<String>, Integer, WikiSimComparableResultList<Double>, Integer, Double, Double, Double, Integer, Integer, Integer>
        > {

    @Override
    public void coGroup(Iterable<Tuple2<String, ArrayList<String>>> a, Iterable<Tuple2<String, WikiSimComparableResultList<Double>>> b, Collector<Tuple11<String, ArrayList<String>, Integer, WikiSimComparableResultList<Double>, Integer, Double, Double, Double, Integer, Integer, Integer>> out) throws Exception {
        Iterator<Tuple2<String, ArrayList<String>>> iteratorA = a.iterator();
        Iterator<Tuple2<String, WikiSimComparableResultList<Double>>> iteratorB = b.iterator();

        if (iteratorA.hasNext()) {
            Tuple2<String, ArrayList<String>> recordA = iteratorA.next();
            List<String> seeAlsoList = recordA.getField(1);

            List<WikiSimComparableResult<Double>> sortedList = new ArrayList<>();

            double topK = 0;
            double hrr = 0;
            double mar = 0;

            int[] matches = new int[]{0, 0, 0};

            if (iteratorB.hasNext()) {
                Tuple2<String, WikiSimComparableResultList<Double>> recordB = iteratorB.next();

                sortedList = Ordering.natural().greatestOf(
                        (WikiSimComparableResultList<Double>) recordB.getField(1), 20);

                List<String> resultList = getResultNamesAsList(sortedList);

                topK = EvaluationMeasures.getTopKScore(resultList, seeAlsoList);
                hrr = EvaluationMeasures.getHarmonicReciprocalRank(resultList, seeAlsoList);
                mar = EvaluationMeasures.getMeanAveragePrecision(resultList, seeAlsoList);

                matches = EvaluationMeasures.getMatchesCount(resultList, seeAlsoList);
            }

            out.collect(new Tuple11<>(
                    (String) recordA.getField(0),
                    (ArrayList<String>) recordA.getField(1),
                    ((ArrayList<String>) recordA.getField(1)).size(),
                    new WikiSimComparableResultList<Double>(sortedList),
                    sortedList.size(),
                    hrr,
                    topK,
                    mar,
                    matches[0],
                    matches[1],
                    matches[2]
            ));
        }
    }

    public static List<String> getResultNamesAsList(List<WikiSimComparableResult<Double>> sortedList) {
        List<String> resultList = new ArrayList<>();
        Iterator<WikiSimComparableResult<Double>> iterator = sortedList.listIterator();

        while (iterator.hasNext()) {
            resultList.add(iterator.next().getName());
        }
        return resultList;
    }
}
