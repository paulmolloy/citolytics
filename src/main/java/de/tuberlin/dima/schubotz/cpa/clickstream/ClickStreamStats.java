package de.tuberlin.dima.schubotz.cpa.clickstream;

import com.google.common.collect.Iterators;
import de.tuberlin.dima.schubotz.cpa.utils.WikiSimConfiguration;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.aggregation.Aggregations;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.util.Collector;

public class ClickStreamStats {
    public static void main(String[] args) throws Exception {
        // set up the execution environment
        final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        if (args.length < 2) {
            System.err.println("Parameters missing: INPUT OUTPUT");
            System.exit(1);
        }

        String outputFilename = args[1];

        // Count articles with ClickStream data, target links
        DataSet<Tuple2<Integer, Integer>> output = ClickStream.getClickStreamDataSet(env, args[0])
                .groupBy(0)
                .reduceGroup(new GroupReduceFunction<Tuple3<String, String, Integer>, Tuple2<Integer, Integer>>() {
                    @Override
                    public void reduce(Iterable<Tuple3<String, String, Integer>> in, Collector<Tuple2<Integer, Integer>> out) throws Exception {
                        out.collect(new Tuple2<>(1, Iterators.size(in.iterator())));
                    }
                })
                .aggregate(Aggregations.SUM, 0)
                .and(Aggregations.SUM, 1);

        if (outputFilename.equals("print")) {
            output.print()
                    .setParallelism(1);
        } else {
            output.writeAsCsv(outputFilename, WikiSimConfiguration.csvRowDelimiter, "\t", FileSystem.WriteMode.OVERWRITE)
                    .setParallelism(1);
        }

        env.execute("ClickStream Stats");
    }
}
