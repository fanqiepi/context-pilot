CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSON NOT NULL,
    embedding VECTOR(1024) NOT NULL
);

CREATE INDEX spring_ai_vector_index
    ON vector_store
    USING HNSW (embedding vector_cosine_ops);
