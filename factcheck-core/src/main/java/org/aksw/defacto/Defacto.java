package org.aksw.defacto;


import org.aksw.defacto.boa.BoaPatternSearcher;
import org.aksw.defacto.boa.Pattern;
import org.aksw.defacto.config.DefactoConfig;
import org.aksw.defacto.evidence.Evidence;
import org.aksw.defacto.ml.feature.evidence.AbstractEvidenceFeature;
import org.aksw.defacto.ml.feature.evidence.EvidenceFeatureExtractor;
import org.aksw.defacto.ml.feature.evidence.EvidenceScorer;
import org.aksw.defacto.ml.feature.fact.AbstractFactFeatures;
import org.aksw.defacto.ml.feature.fact.FactFeatureExtraction;
import org.aksw.defacto.ml.feature.fact.FactScorer;
import org.aksw.defacto.ml.feature.fact.impl.WordnetExpensionFeature;
import org.aksw.defacto.model.DefactoModel;
import org.aksw.defacto.search.cache.solr.Solr4SearchResultCache;
import org.aksw.defacto.search.crawl.EvidenceCrawler;
import org.aksw.defacto.search.query.MetaQuery;
import org.aksw.defacto.search.query.QueryGenerator;
import org.aksw.defacto.util.BufferedFileWriter;
import org.aksw.defacto.util.BufferedFileWriter.WRITER_WRITE_MODE;
import org.aksw.defacto.util.Encoder.Encoding;
import org.aksw.defacto.util.TimeUtil;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.dice.factcheck.search.engine.elastic.ElasticSearchEngine;
import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.classifiers.Classifier;
import weka.core.Instances;

import java.io.*;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Daniel Gerber <dgerber@informatik.uni-leipzig.de>
 */
public class Defacto {

    public enum TIME_DISTRIBUTION_ONLY {

        YES,
        NO;
    }

    public static DefactoConfig DEFACTO_CONFIG;
    public static TIME_DISTRIBUTION_ONLY onlyTimes;

    private static final Logger LOGGER = LoggerFactory.getLogger(Defacto.class);
    private static Classifier machineLearningClassifier;
    private static Classifier factLearningClassifier;
    private static Instances instances;

    public static HttpSolrClient enIndex;
    public static HttpSolrClient deIndex;
    public static HttpSolrClient frIndex;


    public static void init() {

        try {

            if (Defacto.DEFACTO_CONFIG == null)
                Defacto.DEFACTO_CONFIG = new DefactoConfig(new Ini(new File("defacto.ini")));

        } catch (IOException e) {
            LOGGER.error("Error occurred while loading defacto.ini", e);
        }

        //Load evidence machine learning model
        File evidenceLearningModel = new File(DefactoConfig.DEFACTO_DATA_DIR + Defacto.DEFACTO_CONFIG.getStringSetting("evidence", "EVIDENCE_CLASSIFIER_TYPE"));

        if (evidenceLearningModel.exists()) {

            try {
                LOGGER.info("Loading evidence classifier model: " + Defacto.DEFACTO_CONFIG.getStringSetting("evidence", "EVIDENCE_CLASSIFIER_TYPE"));
                machineLearningClassifier = (Classifier) weka.core.SerializationHelper.read(evidenceLearningModel.getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            throw new RuntimeException("No classifier found at " + evidenceLearningModel.getAbsolutePath());
        }

        //Load fact learning model
        File factLearningModel = new File(DefactoConfig.DEFACTO_DATA_DIR + Defacto.DEFACTO_CONFIG.getStringSetting("fact", "FACT_CLASSIFIER_TYPE"));

        try {
            LOGGER.info("Loading fact classifier model: " + Defacto.DEFACTO_CONFIG.getStringSetting("fact", "FACT_CLASSIFIER_TYPE"));

            factLearningClassifier = (Classifier) weka.core.SerializationHelper.read(factLearningModel.getAbsolutePath());

            instances = new Instances(new BufferedReader(new FileReader(
                    DefactoConfig.DEFACTO_DATA_DIR + Defacto.DEFACTO_CONFIG.getStringSetting("fact", "ARFF_TRAINING_DATA_FILENAME"))));
        } catch (Exception e) {

            throw new RuntimeException("Could not load classifier from: " +
                    DefactoConfig.DEFACTO_DATA_DIR + Defacto.DEFACTO_CONFIG.getStringSetting("fact", "FACT_CLASSIFIER_TYPE"), e);
        }


        /*

         */
        ElasticSearchEngine.init();
        Solr4SearchResultCache.init();
        BoaPatternSearcher.init();
        WordnetExpensionFeature.init();

    }

    /**
     * @param model the model to check. this model may only contain the link between two resources
     *              which needs to be checked and the labels (Constants.RESOURCE_LABEL) for the resources which means it
     *              needs to contain only these three triples
     * @return
     */
    public static Evidence checkFact(DefactoModel model, TIME_DISTRIBUTION_ONLY onlyTimes) {

        //init();
        LOGGER.info("Checking fact: " + model);
        Defacto.onlyTimes = onlyTimes;

        // hack to get surface forms before timing
        // not needed anymore, since surfaceforms are inside model
        // SubjectObjectFactSearcher.getInstance();
        // not needed anymore since we do not use NER tagging
        // NlpModelManager.getInstance();

        // 1. generate the search engine queries
        long start = System.currentTimeMillis();
        QueryGenerator queryGenerator = new QueryGenerator(model);
        Map<Pattern, MetaQuery> queries = new HashMap<Pattern, MetaQuery>();
        for (String language : model.languages)
            queries.putAll(queryGenerator.getSearchEngineQueries(language));

        if (queries.size() <= 0) return new Evidence(model);
        LOGGER.info("Preparing queries took " + TimeUtil.formatTime(System.currentTimeMillis() - start));

        // 2. download the search results in parallel
        long startCrawl = System.currentTimeMillis();
        EvidenceCrawler crawler = new EvidenceCrawler(model, queries);
        Evidence evidence = crawler.crawlEvidence();
        LOGGER.info("Crawling evidence took " + TimeUtil.formatTime(System.currentTimeMillis() - startCrawl));

        // short cut to avoid unnecessary computation
        if (onlyTimes.equals(TIME_DISTRIBUTION_ONLY.YES)) return evidence;

        // 3. confirm the facts
        long startFactConfirmation = System.currentTimeMillis();
        FactFeatureExtraction factFeatureExtraction = new FactFeatureExtraction();
        factFeatureExtraction.extractFeatureForFact(evidence);
        LOGGER.info("Fact feature extraction took " + TimeUtil.formatTime(System.currentTimeMillis() - startFactConfirmation));

        //
        // 4. score the facts
        long startFactScoring = System.currentTimeMillis();
        FactScorer factScorer = new FactScorer(factLearningClassifier, instances);
        factScorer.scoreEvidence(evidence);
        LOGGER.info("Fact Scoring took " + TimeUtil.formatTime(System.currentTimeMillis() - startFactScoring));

        // 5. calculate the factFeatures for the model
        long startFeatureExtraction = System.currentTimeMillis();
        EvidenceFeatureExtractor featureCalculator = new EvidenceFeatureExtractor();
        featureCalculator.extractFeatureForEvidence(evidence);
        LOGGER.info("Evidence feature extraction took " + TimeUtil.formatTime(System.currentTimeMillis() - startFeatureExtraction));

        if (!Defacto.DEFACTO_CONFIG.getBooleanSetting("settings", "TRAINING_MODE")) {

            long startScoring = System.currentTimeMillis();
            EvidenceScorer scorer = new EvidenceScorer(machineLearningClassifier);
            scorer.scoreEvidence(evidence);
            LOGGER.info("Evidence Scoring took " + TimeUtil.formatTime(System.currentTimeMillis() - startScoring));

        }

        LOGGER.info("Overall time for fact: " + TimeUtil.formatTime(System.currentTimeMillis() - start));

        return evidence;
    }

    /**
     * @param defactoModel
     * @param onlyTimeDistribution
     * @return
     * @throws IOException
     */
    public static Map<DefactoModel, Evidence> checkFacts(List<DefactoModel> defactoModel, TIME_DISTRIBUTION_ONLY onlyTimeDistribution) throws IOException {

        Map<DefactoModel, Evidence> evidences = new HashMap<DefactoModel, Evidence>();

        for (DefactoModel model : defactoModel) {

            Evidence evidence = checkFact(model, onlyTimeDistribution);
            evidences.put(model, evidence);

            // we want to print the score of the classifier
            if (!Defacto.DEFACTO_CONFIG.getBooleanSetting("settings", "TRAINING_MODE"))
                System.out.println("Defacto: " + new DecimalFormat("0.00").format(evidence.getDeFactoScore()) + " % that this fact is true!");

            // rewrite the fact training file after every proof
            if (DEFACTO_CONFIG.getBooleanSetting("fact", "OVERWRITE_FACT_TRAINING_FILE"))
                writeFactTrainingDataFile(DEFACTO_CONFIG.getStringSetting("fact", "FACT_TRAINING_DATA_FILENAME"));

            // rewrite the training file after every checked triple
            if (DEFACTO_CONFIG.getBooleanSetting("evidence", "OVERWRITE_EVIDENCE_TRAINING_FILE"))
                writeEvidenceTrainingDataFile(DEFACTO_CONFIG.getStringSetting("evidence", "EVIDENCE_TRAINING_DATA_FILENAME"));
            //Defacto.wirteModel(m, model.model, "award_00001.ttl", "http://dbpedia.org/ontology/recievedAward", "http://dbpedia.org/ontology/award", (float)0.0, true);
        }
        return evidences;
    }

    public static void writeEvidenceTrainingFiles(String filename) {

        // rewrite the training file after every checked triple
        if (DEFACTO_CONFIG.getBooleanSetting("evidence", "OVERWRITE_EVIDENCE_TRAINING_FILE"))
            writeEvidenceTrainingDataFile(filename);
    }

    /**
     *
     */
    private static void writeEvidenceTrainingDataFile(String filename) {

        BufferedFileWriter writer = new BufferedFileWriter(DefactoConfig.DEFACTO_DATA_DIR + filename, Encoding.UTF_8, WRITER_WRITE_MODE.OVERRIDE);
        PrintWriter out = new PrintWriter(writer);
        out.println(AbstractEvidenceFeature.provenance.toString());
        writer.close();
    }

    public static void writeFactTrainingFiles(String filename) {

        // rewrite the fact training file after every proof
        if (DEFACTO_CONFIG.getBooleanSetting("fact", "OVERWRITE_FACT_TRAINING_FILE"))
            writeFactTrainingDataFile(filename);
    }

    /**
     * this tries to write an arff file which is also compatible with google docs spreadsheets
     */
    private static void writeFactTrainingDataFile(String filename) {

        try {

            BufferedWriter writer = new BufferedWriter(new FileWriter(DefactoConfig.DEFACTO_DATA_DIR + filename, false));
            PrintWriter out = new PrintWriter(writer);
            out.println(AbstractFactFeatures.factFeatures.toString());
            writer.close();
        } catch (IOException e) {

            e.printStackTrace();
        }
    }
}
