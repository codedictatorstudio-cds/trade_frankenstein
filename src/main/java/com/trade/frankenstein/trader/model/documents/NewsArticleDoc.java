package com.trade.frankenstein.trader.model.documents;

import com.trade.frankenstein.trader.common.constants.NewsVectorsConfigConsts;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = NewsVectorsConfigConsts.NEWS_COLLECTION)
public class NewsArticleDoc {

    @Id
    private String id;
    @Indexed
    private String contentHash; // quick dup guard
    private String title;
    private String description;
    private String source;
    private String url;
    private String lang;
    private long publishedAt;           // epoch millis
    private double[] embedding;         // length = EMBEDDING_DIM
    private String topic;

    // getters/setters...
}