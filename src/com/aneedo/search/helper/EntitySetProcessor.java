package com.aneedo.search.helper;

import gnu.trove.TIntDoubleHashMap;
import gnu.trove.TObjectIntHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aneedo.indexing.IndexingConstants;
import com.aneedo.search.SemClassStore;
import com.aneedo.search.bean.CompatibleEntitySet;
import com.aneedo.search.bean.EntityPair;
import com.aneedo.search.bean.SemEntityBean;
import com.aneedo.search.bean.SemInterpretation;
import com.aneedo.search.ranking.features.node.NodeFeatures;
import com.aneedo.search.util.SemClassParamConstants;
import com.aneedo.search.util.SemanticSearchUtil;
import com.aneedo.search.util.WordFreqStore;
import com.aneedo.util.PorterStemmer;

public class EntitySetProcessor {

	private static final EntitySetProcessor entProcessor = new EntitySetProcessor();

	public static EntitySetProcessor getInstance() {
		return entProcessor;
	}

	private EntitySetProcessor() {

	}

	public void processEntitySet(SemClassStore semClassStore) {

		PorterStemmer stemmer = new PorterStemmer();

		// get query weighted POS vector for query dot product
		double[] queryPOSVector = SemanticSearchUtil.getInstance()
				.getQueryPOSVector(semClassStore.getQueryDetailBean());
		
		// Consider only Noun, ADJ and VB for normalization
		int queryWordsNormalize = SemanticSearchUtil.getInstance().getNNVBADJCount(
				semClassStore.getQueryDetailBean().getPostags());

		// Create global DF and IDF vectors
		WordFreqStore wordFreqStore = SemanticSearchUtil.getInstance()
				.generateDFTFIDFVectors(semClassStore.getSemEntityBeanMap(),
						semClassStore.getPageIds(), stemmer);

		List<CompatibleEntitySet> compatEntList = semClassStore
				.getCompatibleList();

		// New Map to store candidate interpretations
		Map<String, SemInterpretation> semInterpretMap = new HashMap<String, SemInterpretation>();

		// Collect all entities not matching title with query words
		List<Integer> noMatchIdList = semClassStore.getNoMatchEntityList();
		List<SemEntityBean> noTitleMatchEntityList = new ArrayList<SemEntityBean>();

		String[] querySplits = semClassStore.getQueryDetailBean()
				.getQuerySplits();

		double idcg = semClassStore.getQueryDetailBean().getQueryIDCG();

		int noOfSets = compatEntList.size();
		
		int noOfEntitiesInQuery = semClassStore.getSizeOfLargerSet();

		// Collect all entities for which title does not match query
		for (int i = 0, size = noMatchIdList.size(); i < size; i++) {
			SemEntityBean semEntityBean = semClassStore
					.getSemEntity(noMatchIdList.get(i));
			semEntityBean.setQueryDotProduct(SemanticSearchUtil.getInstance()
					.getQueryMatch(semEntityBean, queryPOSVector));
			noTitleMatchEntityList.add(semEntityBean);

			// when a quality is good then only consider it for interpretation
			if (semEntityBean.getQueryDotProduct() > SemClassParamConstants.ENTITY_QUALITY_THRESHOLD) {

				String title = semEntityBean.getTitle();

				// If the disambiguation page then dont consider
				if (title.indexOf("disambiguation") >= 0)
					continue;

				if (semEntityBean.getDisamb() != null) {
					title = title + " " + semEntityBean.getDisamb();
				}

				SemInterpretation tempSemInterpret = new SemInterpretation();
				tempSemInterpret.setInterpretation(title);
				tempSemInterpret.setTitleMatch(1);
				tempSemInterpret.setOverlapSemMembersDtls("//"
						+ semEntityBean.getPageId() + "//");

				// Set all node features
				NodeFeatures nodeFeatures = new NodeFeatures();
				TIntDoubleHashMap featureValueMap = new TIntDoubleHashMap();
				featureValueMap.put(NodeFeatures.TFIDF, wordFreqStore
						.getTfIDF()[semEntityBean.getTitleWordId()]);
				featureValueMap
						.put(NodeFeatures.DF,
								wordFreqStore.getTfIDF()[semEntityBean
										.getTitleWordId()]);

				// modify this while adding recurring interpretations.
				/*
				 * Frequent, co-occurrence and reference are common for title,
				 * let these count do not create a bias
				 */
				featureValueMap.put(NodeFeatures.NO_OF_SEM_CLASS_MATCH, 1);
				featureValueMap.put(NodeFeatures.INTER_SET_SCORE, (double) 1
						/ noOfSets);
				featureValueMap.put(NodeFeatures.INTRA_SET_SCORE, 0);
				if (semClassStore.getSizeOfLargerSet() == 1) {
					featureValueMap.put(
							NodeFeatures.GRANULARITY_EVIDENCE_SCORE,
							SemClassParamConstants.GRANULARITY_TITLE_SCORE);
				} else
					featureValueMap.put(
							NodeFeatures.GRANULARITY_EVIDENCE_SCORE, 0);

				// Set features and add as candidate interpretation
				nodeFeatures.setFeatureValueMap(featureValueMap);
				tempSemInterpret.setNodeFeatures(nodeFeatures);
				semInterpretMap.put(tempSemInterpret.getInterpretation(),
						tempSemInterpret);
			}
		}

		// Process each compatible set
		String title = null;
		for (int i = 0; i < noOfSets; i++) {

			final List<SemEntityBean> semEntityList = new ArrayList<SemEntityBean>();
			final CompatibleEntitySet compatEntitySet = compatEntList.get(i);
			final Set<Integer> nodeSet = compatEntitySet.getPageIdList();
			final Iterator<Integer> nodeItr = nodeSet.iterator();
			double queryDotProduct = 0;

			while (nodeItr.hasNext()) {
				SemEntityBean semEntityBean = semClassStore
						.getSemEntity(nodeItr.next());
				
				title = semEntityBean.getTitle();

				if (semEntityBean.getQueryDotProduct() == 0.0) {
					semEntityBean.setQueryDotProduct(SemanticSearchUtil
							.getInstance().getQueryMatch(semEntityBean,
									queryPOSVector));

					// If all query words present consider as candidates, if the
					// disambiguation page then don't consider
					if (semEntityBean.getQueryDotProduct() > 0.7
							&& title.indexOf("disambiguation") < 0) {
						title = semEntityBean.getTitle();

						if (semEntityBean.getDisamb() != null) {
							title = title + " " + semEntityBean.getDisamb();
						}
						final String[] titleSplits = title.split(" ");
						final double[] softMatch = new double[querySplits.length];
						final boolean[] hardMatch = new boolean[querySplits.length];
						SemanticSearchUtil.getInstance().computeSimilarity(
								querySplits, titleSplits, softMatch, hardMatch);
						SemInterpretation tempSemInterpret = new SemInterpretation();
						tempSemInterpret.setInterpretation(title);
						tempSemInterpret.setTitleMatch(1);
						tempSemInterpret.setQueryMatch(SemanticSearchUtil
								.getInstance().getNDCGSimilarity(
										semEntityBean.getSoftTitleQueryMatch(),
										semEntityBean.getMatchedPositions(),
										hardMatch, idcg));
						tempSemInterpret.setOverlapSemMembersDtls("//"
								+ semEntityBean.getPageId() + "//");

						// Set all node features
						NodeFeatures nodeFeatures = new NodeFeatures();
						TIntDoubleHashMap featureValueMap = new TIntDoubleHashMap();
						featureValueMap.put(NodeFeatures.TFIDF, wordFreqStore
								.getTfIDF()[semEntityBean.getTitleWordId()]);
						featureValueMap.put(NodeFeatures.DF, wordFreqStore
								.getDF()[semEntityBean.getTitleWordId()]);

						// modify this while adding recurring interpretations.
						/*
						 * Frequent, co-occurrence and reference are common for
						 * title, let these count do not create a bias
						 */
						featureValueMap.put(NodeFeatures.NO_OF_SEM_CLASS_MATCH,
								1);
						featureValueMap.put(NodeFeatures.INTER_SET_SCORE,
								(double) 1 / noOfSets);
						featureValueMap.put(NodeFeatures.INTRA_SET_SCORE, 0);
						if (semClassStore.getSizeOfLargerSet() == 1) {
							featureValueMap
									.put(
											NodeFeatures.GRANULARITY_EVIDENCE_SCORE,
											SemClassParamConstants.GRANULARITY_TITLE_SCORE);
						} else
							featureValueMap.put(
									NodeFeatures.GRANULARITY_EVIDENCE_SCORE, 0);

						nodeFeatures.setFeatureValueMap(featureValueMap);
						tempSemInterpret.setNodeFeatures(nodeFeatures);
						semInterpretMap.put(tempSemInterpret
								.getInterpretation(), tempSemInterpret);

					}
				}

				queryDotProduct = semEntityBean.getQueryDotProduct()
						+ queryDotProduct;
				semEntityList.add(semEntityBean);
			}

			// Defer the decision of candidate interpretation until set confirms
			// to be good
			compatEntitySet.setQueryDotProduct(queryDotProduct);
			double overlapScore = 0.0;
			int titleNormalize = semEntityList.size() * queryWordsNormalize;

			// Judge the quality of each set
			manipulateEntitySet(compatEntitySet, semEntityList,
					noTitleMatchEntityList, wordFreqStore, stemmer);

			double[] overlapScoreList = compatEntitySet.getOverlapCountList();
			for (int k = 0; k < overlapScoreList.length; k++) {
				if(overlapScoreList[k] > 0)
					overlapScore = overlapScore + (double) overlapScoreList[k]/overlapScoreList.length;
			}

			final double compatibleScore = (double) compatEntitySet.getPairwiseTitleMatch()
					+ compatEntitySet.getQueryDotProduct() + overlapScore 
					+ SemClassParamConstants.DF_TFIDF_TRADEOFF * compatEntitySet.getTfIdfScore()
					+(1-SemClassParamConstants.DF_TFIDF_TRADEOFF) * compatEntitySet.getDfScore();
			
			final double entSetScore = (double) SemClassParamConstants.LAMDA
					* (compatEntitySet.getTitleMatchScore() / titleNormalize)
					+ (1 - SemClassParamConstants.LAMDA) * compatibleScore;
			
			compatEntitySet.setEntitySetScore(entSetScore);
			compatEntitySet.setFeatureScore(compatibleScore);
		}
		
		// Sort compatible set based on score
		CompatibleEntitySet[] compatEntities = new CompatibleEntitySet[noOfSets];
		compatEntities = compatEntList.toArray(compatEntities);
		Arrays.sort(compatEntities, new CompatibleSetComparator());

		// Process only if good set 
		for (int i = 0; i < noOfSets; i++) {
			TObjectIntHashMap<String> wordToIdMap = wordFreqStore.getWordToIdMap();
			final CompatibleEntitySet compatEntitySet = compatEntities[i];
			if (compatEntitySet.getOverlapSemMembers() != null 
					//&& compatEntitySet.getEntitySetScore() > SemClassParamConstants.ENTITY_QUALITY_THRESHOLD
					) {
				
				// TODO pick even frequent, frequent association, section heading reference of good set
				
				Iterator<String> setInterpretItr = compatEntitySet
						.getOverlapSemMembers().keySet().iterator();
				while (setInterpretItr.hasNext()) {
					String key = setInterpretItr.next();
					String[] splits = key.split(" ");
					if (splits.length <= 1)
						continue;
					if (!semInterpretMap.containsKey(key)) {

						final String[] titleSplits = key.split(" ");
						SemanticSearchUtil.getInstance().computeSimilarity(
								querySplits, titleSplits);
						SemInterpretation tempSemInterpret = compatEntitySet
								.getOverlapSemMembers().get(key);
						tempSemInterpret.setInterpretation(key);
						tempSemInterpret.setTitleMatch(1);
						tempSemInterpret.setQueryMatch(SemanticSearchUtil
								.getInstance().computeSimilarity(querySplits,
										titleSplits));

						// tempSemInterpret.setAggScore(entSetScore);
						// Set all node features
						final int index = wordToIdMap.get(key);
						NodeFeatures nodeFeatures = new NodeFeatures();
						TIntDoubleHashMap featureValueMap = new TIntDoubleHashMap();
						featureValueMap.put(NodeFeatures.TFIDF, wordFreqStore
								.getTfIDF()[index]);
						featureValueMap.put(NodeFeatures.DF, wordFreqStore
								.getDF()[index]);

						// modify this while adding recurring interpretations.
						/*
						 * Frequent, co-occurrence and reference are common for
						 * title, let these counts do not create bias
						 */
						// TODO split member dtls
						featureValueMap.put(NodeFeatures.NO_OF_SEM_CLASS_MATCH,
								1);
						featureValueMap.put(NodeFeatures.INTER_SET_SCORE,
								(double) 1 / noOfSets);
						featureValueMap.put(NodeFeatures.INTRA_SET_SCORE,
								tempSemInterpret.getIntraSetScore());
						
						// TODO only for section template heading, overlap association, co-occurence and frequent, reference 
						if (semClassStore.getSizeOfLargerSet() > 1) {
							featureValueMap
									.put(NodeFeatures.GRANULARITY_EVIDENCE_SCORE,
										 1/noOfEntitiesInQuery * SemClassParamConstants.GRANULARITY_EVIDENCE_SCORE);
						} else
							featureValueMap.put(
									NodeFeatures.GRANULARITY_EVIDENCE_SCORE, 0);

						nodeFeatures.setFeatureValueMap(featureValueMap);
						tempSemInterpret.setNodeFeatures(nodeFeatures);
						semInterpretMap.put(key, compatEntitySet
								.getOverlapSemMembers().get(key));
					} else {
						// TODO find GM of inter set score
					}
					
				}
			}
		}

		// Set ranked interpretation to return
		List<SemInterpretation> semInterList = new ArrayList<SemInterpretation>();
		semInterList.addAll(semInterpretMap.values());
		semClassStore.setSemInterpretationList(semInterList);

	}

	private void manipulateEntitySet(CompatibleEntitySet compatEntSet,
			List<SemEntityBean> semEntityList,
			List<SemEntityBean> noTitleMatchEntityList,
			WordFreqStore wordFreqStore, PorterStemmer stemmer) {
		EntityPair entPair = new EntityPair();
		boolean isSetQualityGood = false;
		Map<String, SemInterpretation> overlapSemMembers = new HashMap<String, SemInterpretation>();
		Map<String, String> interPairSemMems = new HashMap<String, String>();
		//double intraSetScore = compatEntSet.getIntraPairScore();
		// TODO TF-IDF to be computed
		double pairwiseTitleScore = 0;
		List<double[]> overlapCountList = new ArrayList<double[]>();

		for (int i = 0, size = semEntityList.size(); i < size; i++) {

			for (int j = i + 1; j < size; j++) {

				entPair.setSemEntityL(semEntityList.get(i));
				entPair.setSemEntityR(semEntityList.get(j));
				processPairwise(entPair, overlapSemMembers, interPairSemMems,
						stemmer);

				//intraSetScore = intraSetScore + entPair.getIntraPairScore();
				pairwiseTitleScore = pairwiseTitleScore
						+ entPair.getTitlePairwise();
				overlapCountList.add(entPair.getOverlapCount());

				if (!isSetQualityGood) {
					isSetQualityGood = entPair.isOverlapExist();
				}
				entPair.clear();
			}
		}

		// If set is not good, do not add because it will start favoring
		// keywords
		// Add only those no title match entities to set which are compatible
		if (isSetQualityGood) {
			for (int i = 0, size = semEntityList.size(); i < size; i++) {
				for (int j = 0, noMatchSize = noTitleMatchEntityList.size(); j < noMatchSize; j++) {

					entPair.setSemEntityL(semEntityList.get(i));
					final SemEntityBean semEntityBeanR = noTitleMatchEntityList
							.get(j);
					entPair.setSemEntityR(semEntityBeanR);
					processPairwise(entPair, overlapSemMembers,
							interPairSemMems, stemmer);

					if (entPair.isOverlapExist()) {
						compatEntSet.getPageIdList().add(
								semEntityBeanR.getPageId());
						// intraSetScore = intraSetScore+entPair.getIntraPairScore();
						pairwiseTitleScore = pairwiseTitleScore
								+ entPair.getTitlePairwise();
						overlapCountList.add(entPair.getOverlapCount());
						
						// Accumulate Query dot product score
						compatEntSet.setQueryDotProduct(compatEntSet
								.getQueryDotProduct()
								+ semEntityBeanR.getQueryDotProduct());
					}

				}
			}

			int compatEntSetSize = compatEntSet.getPageIdList().size();
			compatEntSet.setPairwiseTitleMatch(pairwiseTitleScore
					/ (compatEntSetSize * compatEntSetSize - 1));
			compatEntSet.setQueryDotProduct(compatEntSet.getQueryDotProduct()
					/ compatEntSetSize);
			compatEntSet.setOverlapSemMembers(overlapSemMembers);

			// set intraset score
			Iterator<String> semMemItr = interPairSemMems.keySet().iterator();
			Set<String> entityIdSet = new HashSet<String>();

			while (semMemItr.hasNext()) {
				final String semMem = semMemItr.next();
				final String entIds = interPairSemMems.get(semMem);
				String[] splits = entIds.split("/");
				for (int i = 0; i < splits.length; i++) {
					entityIdSet.add(splits[i]);
				}
				// final SemInterpretation semInterpretation =
				// overlapSemMembers.get(semMem);
				// semInterpretation.setIntraSetScore(Math.log(interPairSemMems.size()))
			}
			double intraSetScore = Math.log((entityIdSet.size() + 1)
					/ interPairSemMems.size());
			compatEntSet.setIntraPairScore(intraSetScore);

			double[] overlapScore = new double[3];
			for (int i = 0, size = overlapCountList.size(); i < size; i++) {
				overlapScore[0] = (double) overlapScore[0]
						+ overlapCountList.get(i)[0] / size;
				overlapScore[1] = (double) overlapScore[1]
						+ overlapCountList.get(i)[1] / size;
				overlapScore[2] = (double) overlapScore[2]
						+ overlapCountList.get(i)[2] / size;
			}
		}
	}

	private void processPairwise(EntityPair entPair,
			Map<String, SemInterpretation> overlapSemMembers,
			Map<String, String> interPairSemMems, PorterStemmer stemmer) {

		SemanticSearchUtil util = SemanticSearchUtil.getInstance();

		// Do title pairwise match
		int[] semClassLabels = new int[] {
				IndexingConstants.INT_SEM_CLASS_SYNOPSIS,
				IndexingConstants.INT_SECTIONS,
				IndexingConstants.INT_SEM_CLASS_ASSOCIATION_INLINK,
				IndexingConstants.INT_SEM_CLASS_ASSOCIATION_HIERARCHY,
				IndexingConstants.INT_SEM_CLASS_HYPERNYM,
				IndexingConstants.INT_SEM_CLASS_HYPONYM,
				IndexingConstants.INT_SEM_CLASS_REFERENCE,
				IndexingConstants.INT_SEM_CLASS_SIBLING,
				IndexingConstants.INT_SEM_CLASS_FREQUENT,
				IndexingConstants.INT_SEM_CLASS_ASSOCIATION_RELATED };

		Number[] titleMatch = util.getTitleMatch(entPair.getSemEntityL(),
				entPair.getSemEntityR(), stemmer, semClassLabels)[1];
		double pairwiseTitleScore = 0;
		for (int i = 0; i < titleMatch.length; i++) {
			if (titleMatch[i] != null) {
				pairwiseTitleScore = (double) pairwiseTitleScore
						+ (Double) titleMatch[i];
			}
		}
		entPair.setTitlePairwise(pairwiseTitleScore);

		// Do definition
		util.commonSemMembers(overlapSemMembers, interPairSemMems, new int[] {
				IndexingConstants.INT_SEM_CLASS_SYNOPSIS,
				IndexingConstants.INT_SECTIONS }, entPair, 0);

		// Do content overlap
		semClassLabels = new int[] {
				IndexingConstants.INT_SEM_CLASS_ASSOCIATION_INLINK,
				IndexingConstants.INT_SEM_CLASS_FREQUENT,
				IndexingConstants.INT_SEM_CLASS_REFERENCE,
				IndexingConstants.INT_SEM_CLASS_ASSOCIATION_RELATED };
		util.commonSemMembers(overlapSemMembers, interPairSemMems,
				semClassLabels, entPair, 1);

		// Do folksonomy overlap
		semClassLabels = new int[] {
				IndexingConstants.INT_SEM_CLASS_ASSOCIATION_HIERARCHY,
				IndexingConstants.INT_SEM_CLASS_HYPERNYM,
				IndexingConstants.INT_SEM_CLASS_HYPONYM,
				IndexingConstants.INT_SEM_CLASS_SIBLING };

		util.commonSemMembers(overlapSemMembers, interPairSemMems,
				semClassLabels, entPair, 1);
	}

}

class CompatibleSetComparator implements Comparator {
	public int compare(Object obj1, Object obj2) {

		CompatibleEntitySet compatEntSetL = ((CompatibleEntitySet) obj1);

		CompatibleEntitySet compatEntSetR = ((CompatibleEntitySet) obj2);

		if (compatEntSetL.getEntitySetScore() < compatEntSetR.getEntitySetScore())
			
			return 1;

		else if (compatEntSetL.getEntitySetScore() > compatEntSetL.getEntitySetScore())

			return -1;

		else

			return 0;

	}
}
