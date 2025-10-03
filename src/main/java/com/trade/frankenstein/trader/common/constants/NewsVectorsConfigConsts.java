package com.trade.frankenstein.trader.common.constants;

import java.time.Duration;

public final class NewsVectorsConfigConsts {

    private NewsVectorsConfigConsts() {
    }

    // === Collections & indexes ===
    public static final String DB_NAME = "TFS";
    public static final String NEWS_COLLECTION = "news_articles";
    public static final String VECTOR_INDEX_NAME = "news_embedding_idx"; // Atlas Vector Search index name
    public static final String TEXT_INDEX_NAME = "news_text_idx";

    // === Field names (persisted) ===
    public static final String F_ID = "_id";
    public static final String F_TITLE = "title";
    public static final String F_DESC = "description";
    public static final String F_SOURCE = "source";
    public static final String F_PUBLISHED_AT = "publishedAt";
    public static final String F_URL = "url";
    public static final String F_EMBEDDING = "embedding";           // double[] (size = EMBEDDING_DIM)
    public static final String F_TOPIC = "topic";                    // clustered topic label (optional)
    public static final String F_LANGUAGE = "lang";                  // optional “en”, “hi”, …
    public static final String F_HASH = "contentHash";               // normalized title+desc hash for quick dup guard

    // === Embedding model & dimensionality ===
    // Choose the model you actually use to produce vectors; keep dim in sync!
    public static final String EMBEDDING_MODEL_ID = "all-MiniLM-L6-v2";
    public static final int EMBEDDING_DIM = 384;

    // === Similarity metric (MongoDB Atlas Vector Search supports cosine, dotProduct, euclidean) ===
    public static final String SIMILARITY_METRIC = "cosine";

    // === Dedupe thresholds ===
    // If cosine similarity with an existing headline >= this → treat as duplicate (skip/merge).
    public static final double DEDUPE_SIMILARITY_THRESHOLD = 0.86d;

    // Cluster items if similarity >= this: assign same topic cluster
    public static final double CLUSTER_SIMILARITY_THRESHOLD = 0.80d;

    // === ANN query parameters ===
    public static final int K_NEIGHBORS = 20;              // how many nearest items to probe
    public static final int K_REFINE = 50;                 // refine window for accuracy (Atlas setting)
    public static final int MAX_RESULTS_PER_FEED = 200;    // safety cap per ingest batch

    // === Batch & timeouts ===
    public static final int EMBED_BATCH_SIZE = 64;
    public static final Duration INGEST_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration INGEST_READ_TIMEOUT = Duration.ofSeconds(8);

    // === Housekeeping ===
    public static final int TITLE_MIN_LEN = 6;             // ignore too-short titles
    public static final int DESC_MIN_LEN = 12;
    public static final int MAX_TITLE_LEN = 400;           // truncate before hashing/embedding
    public static final int MAX_DESC_LEN = 2000;

    // === Similarity/merge policy ===
    public static final boolean MERGE_DUPLICATES = true;   // else: skip
    public static final String MERGE_STRATEGY = "prefer_earliest"; // or "prefer_latest"

    // === Feature toggles for Step 11 (use your FlagsService if you have one) ===
    public static final boolean ENABLE_ANN_DEDUPE = true;
    public static final boolean ENABLE_TOPIC_CLUSTERING = true;
}