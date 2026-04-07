package com.example.local_notebooklm.chat.evaluator;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;

@Service
public class GroundingEvaluatorService {

    public double computeGroundingScore(String answer, String context) {
        Set<String> stopwords = Set.of(
                "the","a","an","is","are","was","were","be","been","it","this","that","and","or",
                "but","in","on","at","to","for","of","with","as","by","from","has","have","had",
                "will","would","could","should","may","can","not","do","does","did","its","their",
                "they","we","you","i","he","she","if","also","only","then","very","just","more",
                "such","each","into","about","between","through","during","before","after","above",
                "below","use","used","using","make","made","making");

        String contextLower = context.toLowerCase();
        String[] words      = answer.toLowerCase().split("[\\W]+");

        long total = Arrays.stream(words)
                .filter(w -> w.length() > 3 && !stopwords.contains(w))
                .count();
        if (total == 0) return 1.0;

        long supported = Arrays.stream(words)
                .filter(w -> w.length() > 3 && !stopwords.contains(w) && contextLower.contains(w))
                .count();

        return Math.min(1.0, (double) supported / total);
    }
}