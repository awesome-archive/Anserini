/**
 * Anserini: An information retrieval toolkit built on Lucene
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.rerank.lib;

import io.anserini.rerank.Reranker;
import io.anserini.rerank.RerankerContext;
import io.anserini.rerank.ScoredDocuments;
import io.anserini.search.topicreader.NewsBackgroundLinkingTopicReader;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import static io.anserini.index.generator.LuceneDocumentGenerator.FIELD_BODY;
import static io.anserini.index.generator.LuceneDocumentGenerator.FIELD_ID;


/*
* TREC News Track Background Linking task postprocessing.
* Near-duplicate documents (similar/same with the query docid) will be removed by comparing
* their cosine similarity with the query docid.
*/
public class NewsBackgroundLinkingReranker implements Reranker {
  @Override
  public ScoredDocuments rerank(ScoredDocuments docs, RerankerContext context) {
    IndexReader reader = context.getIndexSearcher().getIndexReader();
    String queryDocId = context.getQueryDocId();
    final Map<String, Long> queryTermsMap = convertDocVectorToMap(reader, queryDocId);
    
    List<Map<String, Long>> docsVectorsMap = new ArrayList<>();
    for (int i = 0; i < docs.documents.length; i++) {
      String docid = docs.documents[i].getField(FIELD_ID).stringValue();
      docsVectorsMap.add(convertDocVectorToMap(reader, docid));
    }
    
    // remove the duplicates: 1. the same doc with the query doc 2. duplicated docs in the results
    Set<Integer> duplicates = new HashSet<>();
    for (int i = 0; i < docs.documents.length; i++) {
      if (duplicates.contains(i)) continue;
      String docid = docs.documents[i].getField(FIELD_ID).stringValue();
      if (computeCosineSimilarity(queryTermsMap, docsVectorsMap.get(i)) >= 0.9) {
        duplicates.add(i);
        continue;
      }
      for (int j = i+1; j < docs.documents.length; j++) {
        if (computeCosineSimilarity(docsVectorsMap.get(i), docsVectorsMap.get(j)) >= 0.9) {
          duplicates.add(j);
        }
      }
    }
  
    ScoredDocuments scoredDocs = new ScoredDocuments();
    int resSize = docs.documents.length - duplicates.size();
    scoredDocs.documents = new Document[resSize];
    scoredDocs.ids = new int[resSize];
    scoredDocs.scores = new float[resSize];
    int idx = 0;
    for (int i = 0; i < docs.documents.length; i++) {
      if (!duplicates.contains(i)) {
        scoredDocs.documents[idx] = docs.documents[i];
        scoredDocs.scores[idx] = docs.scores[i];
        scoredDocs.ids[idx] = docs.ids[i];
        idx++;
      }
    }
  
    return scoredDocs;
  }
  
  private Map<String, Long> convertDocVectorToMap(IndexReader reader, String docid) {
    Map<String, Long> m = new HashMap<>();
    try {
      Terms terms = reader.getTermVector(
          NewsBackgroundLinkingTopicReader.convertDocidToLuceneDocid(reader, docid), FIELD_BODY);
      TermsEnum it = terms.iterator();
      while (it.next() != null) {
        String term = it.term().utf8ToString();
        long tf = it.totalTermFreq();
        m.put(term, tf);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return m;
  }
  
  private double dotProduct(Map<String, Long> profile1, Map<String, Long> profile2) {
    // Loop over the smallest map
    Map<String, Long> small_profile = profile2;
    Map<String, Long> large_profile = profile1;
    if (profile1.size() < profile2.size()) {
      small_profile = profile1;
      large_profile = profile2;
    }
    
    double agg = 0;
    for (Map.Entry<String, Long> entry : small_profile.entrySet()) {
      long i = large_profile.getOrDefault(entry.getKey(), 0L);
      agg += 1.0 * entry.getValue() * i;
    }
    
    return agg;
  }
  
  private double computeCosineSimilarity(Map<String, Long> profile1, Map<String, Long> profile2) {
    return dotProduct(profile1, profile2) / (computeL2Norm(profile1) * computeL2Norm(profile2));
  }
  
  /**
   * Compute the norm L2 : sqrt(Sum_i( v_i²)).
   *
   * @param profile
   * @return L2 norm
   */
  private double computeL2Norm(final Map<String, Long> profile) {
    double agg = 0;
    
    for (Map.Entry<String, Long> entry : profile.entrySet()) {
      agg += 1.0 * entry.getValue() * entry.getValue();
    }
    
    return Math.sqrt(agg);
  }
  
  @Override
  public String tag() { return ""; }
}
