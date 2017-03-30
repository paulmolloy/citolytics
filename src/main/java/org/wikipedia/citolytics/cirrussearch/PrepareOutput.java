package org.wikipedia.citolytics.cirrussearch;

import org.apache.flink.api.common.functions.FlatJoinFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.util.Collector;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.wikipedia.citolytics.WikiSimAbstractJob;
import org.wikipedia.citolytics.cpa.WikiSim;
import org.wikipedia.citolytics.cpa.types.IdTitleMapping;
import org.wikipedia.citolytics.cpa.types.WikiSimRecommendation;
import org.wikipedia.citolytics.cpa.types.WikiSimResult;
import org.wikipedia.citolytics.cpa.types.WikiSimTopResults;
import org.wikipedia.citolytics.seealso.better.WikiSimGroupReducer;
import org.wikipedia.citolytics.seealso.better.WikiSimReader;
import org.wikipedia.citolytics.seealso.types.WikiSimComparableResult;
import org.wikipedia.citolytics.seealso.types.WikiSimComparableResultList;

/**
 * Preparing WikiSim output to be added to Elasticsearch, i.e. transform to JSON.
 * <p/>
 * TODO Use ES directly as sink?
 *
 * @author malteschwarzer
 */
public class PrepareOutput extends WikiSimAbstractJob<Tuple1<String>> {

    public static void main(String[] args) throws Exception {
        new PrepareOutput().start(args);
    }

    @Override
    public void plan() throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);

        String wikiSimInputFilename = params.get("wikisim"); // not in use

        String wikiDumpInputFilename = params.getRequired("wikidump");

        outputFilename = params.getRequired("output");
        String idTitleMappingFilename = params.get("idtitle-mapping", null);
        String redirectsFilename = params.get("redirects", null);
        int topK = params.getInt("topk", 10);
        int fieldScore = params.getInt("score", 5);
        int fieldPageA = params.getInt("page-a", 1);
        int fieldPageB = params.getInt("page-b", 2);
        boolean disableScores = params.has("disable-scores");
        boolean elasticBulkSyntax = params.has("enable-elastic");
        boolean ignoreMissingIds = params.has("ignore-missing-ids");
        boolean resolveRedirects = params.has("resolve-redirects");
        boolean includeIds = params.has("include-ids");

        setJobName("CirrusSearch PrepareOutput");

        // Load id-title mapping
        DataSet<IdTitleMapping> idTitleMapping = IdTitleMappingExtractor.getIdTitleMapping(env, idTitleMappingFilename, wikiDumpInputFilename);

        // Prepare results
        DataSet<WikiSimTopResults> wikiSimData;
        if(wikiSimInputFilename == null) {

            // Build new result list
            WikiSim wikiSimJob = new WikiSim();
            wikiSimJob.alpha = "0.855"; // From JCDL paper: a1=0.81, a2=0.9 -> a_mean = 0.855
            wikiSimJob.inputFilename = wikiDumpInputFilename;
            wikiSimJob.redirectsFilename = redirectsFilename;
            wikiSimJob.removeMissingIds = !ignoreMissingIds; // Ensures that page ids exist
            wikiSimJob.resolveRedirects = resolveRedirects;
            wikiSimJob.setEnvironment(env);
            wikiSimJob.plan();

            wikiSimData = wikiSimJob.result
                    .flatMap(new FlatMapFunction<WikiSimResult, WikiSimRecommendation>() {
                        private final int alphaKey = 0;
                        @Override
                        public void flatMap(WikiSimResult in, Collector<WikiSimRecommendation> out) throws Exception {

                            out.collect(new WikiSimRecommendation(in.getPageA(), in.getPageB(), in.getCPI(alphaKey), in.getPageAId(), in.getPageBId()));
                            out.collect(new WikiSimRecommendation(in.getPageB(), in.getPageA(), in.getCPI(alphaKey), in.getPageBId(), in.getPageAId()));
                        }
                    })
                    .groupBy(0)
                    .reduceGroup(new WikiSimGroupReducer(topK));
        } else {
            // Use existing result list;
            wikiSimData = WikiSimReader.readWikiSimOutput(env, wikiSimInputFilename, topK, fieldPageA, fieldPageB, fieldScore);
        }

        // Transform result list to JSON with page ids
        // TODO Check if there some pages lost (left or equi-join)
//        result = wikiSimData.leftOuterJoin(idTitleMapping)
//                .where(0)
//                .equalTo(1)
//                .with(new JSONMapperWithIdCheck(disableScores, elasticBulkSyntax, ignoreMissingIds, includeIds));

        result = wikiSimData.flatMap(new JSONMapper(disableScores, elasticBulkSyntax, ignoreMissingIds, includeIds));

    }


    public class JSONMapper implements FlatMapFunction<WikiSimTopResults, Tuple1<String>> {
        protected boolean disableScores = false;
        protected boolean elasticBulkSyntax = false;
        protected boolean ignoreMissingIds = false;
        protected boolean includeIds = false;

        /**
         * @param disableScores Set to true if score should not be included in JSON output
         */
        JSONMapper(boolean disableScores, boolean elasticBulkSyntax, boolean ignoreMissingIds, boolean includeIds) {
            this.disableScores = disableScores;
            this.elasticBulkSyntax = elasticBulkSyntax;
            this.ignoreMissingIds = ignoreMissingIds;
            this.includeIds = includeIds;
        }

        public void putResultArray(ObjectNode d, WikiSimComparableResultList<Double> results) {
            ArrayNode a = d.putArray("citolytics");

            for (WikiSimComparableResult<Double> result : results) {

                ObjectNode r = a.addObject();
                r.put("title", result.getName());

                if (includeIds)
                    r.put("id", result.getId());

                if (!disableScores)
                    r.put("score", result.getSortField1());
            }
        }

        protected String getDefaultOutput(int pageId, String title, WikiSimComparableResultList<Double> results) {
            // Default output
            // Needs to be transformed (by PySpark script) before can be send to ES
            ObjectMapper m = new ObjectMapper();
            ObjectNode n = m.createObjectNode();

            n.put("id", pageId);
            n.put("title", title);
//            n.put("namespace", 0);
            putResultArray(n, results);

            return n.toString();
        }

        protected String getElasticBulkOutput(int pageId, WikiSimComparableResultList<Double> results) {
            // Return JSON that is already in ES bulk syntax
            // https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html
            // { "update" : {"_id" : "1", "_type" : "type1", "_index" : "index1", "_retry_on_conflict" : 3} }
            // { "doc" : {"field" : "value"} }

            ObjectMapper m = new ObjectMapper();
            ObjectNode a = m.createObjectNode();
            ObjectNode u = a.putObject("update");
            u.put("_id", pageId);

            ObjectNode s = m.createObjectNode();
            ObjectNode d = s.putObject("doc");

            putResultArray(d, results);

            return a.toString() + "\n" + s.toString(); // action_and_meta_data \n source
        }

        @Override
        public void flatMap(WikiSimTopResults in, Collector<Tuple1<String>> out) throws Exception {
            int pageId = in.getSourceId();

            // Check for page id
            if(!ignoreMissingIds && pageId < 0)
                return;

            out.collect(new Tuple1<>(elasticBulkSyntax ?
                    getElasticBulkOutput(pageId, in.getResults()) :
                    getDefaultOutput(pageId, in.getSourceTitle(), in.getResults())
            ));
        }
    }

    @Deprecated
    public class JSONMapperWithIdCheck extends JSONMapper implements FlatJoinFunction<WikiSimTopResults, IdTitleMapping, Tuple1<String>> {

        /**
         * @param disableScores     Set to true if score should not be included in JSON output
         * @param elasticBulkSyntax
         * @param ignoreMissingIds
         * @param includeIds
         */
        JSONMapperWithIdCheck(boolean disableScores, boolean elasticBulkSyntax, boolean ignoreMissingIds, boolean includeIds) {
            super(disableScores, elasticBulkSyntax, ignoreMissingIds, includeIds);
        }

        @Override
        public void join(WikiSimTopResults in, IdTitleMapping mapping, Collector<Tuple1<String>> out) throws Exception {
            int pageId;

            // Check for page id
            if(mapping == null) {
                if(ignoreMissingIds) {
                    pageId = -1; // Ignore missing page id
                } else {
                    return; // Don't ignore
                }
            } else {
                pageId = mapping.f0;
            }

            out.collect(new Tuple1<>(elasticBulkSyntax ?
                    getElasticBulkOutput(pageId, in.getResults()) :
                    getDefaultOutput(pageId, in.getSourceTitle(), in.getResults())
            ));
        }
    }
}
