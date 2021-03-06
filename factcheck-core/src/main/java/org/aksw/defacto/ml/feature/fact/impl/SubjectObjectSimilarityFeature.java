/**
 * 
 */
package org.aksw.defacto.ml.feature.fact.impl;

import org.aksw.defacto.evidence.ComplexProof;
import org.aksw.defacto.evidence.Evidence;
import org.aksw.defacto.ml.feature.fact.AbstractFactFeatures;
import org.aksw.defacto.ml.feature.fact.FactFeature;

import uk.ac.shef.wit.simmetrics.similaritymetrics.Levenshtein;

/**
 * @author Daniel Gerber <dgerber@informatik.uni-leipzig.de>
 *
 */
public class SubjectObjectSimilarityFeature implements FactFeature {
	
    Levenshtein lev		= new Levenshtein();

    @Override
    public void extractFeature(ComplexProof proof, Evidence evidence) {
    	
    	String subjectLabel = evidence.getModel().getSubjectLabel(proof.getLanguage()).toLowerCase();
    	String objectLabel = evidence.getModel().getObjectLabel(proof.getLanguage()).toLowerCase();
    	
        proof.getFeatures().setValue(AbstractFactFeatures.SUBJECT_SIMILARITY, 
        		Math.max(lev.getSimilarity(proof.getSubject().toLowerCase(), subjectLabel), lev.getSimilarity(proof.getSubject().toLowerCase(), objectLabel)));
        proof.getFeatures().setValue(AbstractFactFeatures.OBJECT_SIMILARITY, 
        		Math.max(lev.getSimilarity(proof.getObject().toLowerCase(), objectLabel), lev.getSimilarity(proof.getObject().toLowerCase(), subjectLabel)));
    }
}
