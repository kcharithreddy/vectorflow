# Qdrant Vector Database Basics

## What is Qdrant?

Qdrant is a vector similarity search engine and vector database. It provides a production-ready service with a convenient API to store, search, and manage points—vectors with an additional payload. Qdrant is written in Rust, making it fast, efficient, and reliable.

## Core Concepts

### Collections

A collection is a named set of points (vectors with payload) among which you can search. All vectors within a collection must have the same dimensionality and be compared using a single metric.

To create a collection, you need to specify:
- **Collection name**: A unique identifier for the collection
- **Vector size**: The dimensionality of vectors (e.g., 1536 for OpenAI ada-002)
- **Distance metric**: How similarity is calculated (Cosine, Euclidean, or Dot Product)

Example of creating a collection:
```python
from qdrant_client import QdrantClient
from qdrant_client.http import models

client = QdrantClient("localhost", port=6333)

client.create_collection(
    collection_name="my_collection",
    vectors_config=models.VectorParams(
        size=1536,
        distance=models.Distance.COSINE
    )
)
```

### Points

A point is the central entity in Qdrant. It consists of:
- **ID**: A unique identifier (integer or UUID)
- **Vector**: The embedding representation
- **Payload**: Optional JSON metadata associated with the vector

Points are upserted (inserted or updated) into collections and can be searched or filtered.

### Payloads

Payloads are arbitrary JSON objects attached to points. They can be used for:
- Storing metadata about the original content
- Filtering search results
- Returning additional information with search results

Common payload fields include:
- `text`: The original text content
- `document_id`: Reference to the source document
- `created_at`: Timestamp of creation
- `category`: Classification label
- `tags`: List of associated tags

## Vector Similarity Search

### How it Works

Vector similarity search finds the most similar vectors to a query vector. The process:
1. Encode your query into a vector using the same embedding model
2. Send the query vector to Qdrant
3. Qdrant returns the most similar points based on the distance metric

### Distance Metrics

**Cosine Similarity**: Measures the angle between vectors. Best for text embeddings.
- Range: -1 to 1 (higher is more similar)
- Normalized vectors required

**Euclidean Distance**: Measures straight-line distance between points.
- Range: 0 to infinity (lower is more similar)
- Good for spatial data

**Dot Product**: Inner product of vectors.
- Best when vector magnitude carries meaning

### Search Example

```python
# Search for similar vectors
results = client.search(
    collection_name="my_collection",
    query_vector=[0.1, 0.2, ...],  # Your query embedding
    limit=5,  # Return top 5 results
    with_payload=True
)

for result in results:
    print(f"Score: {result.score}, Text: {result.payload['text']}")
```

## Filtering

Qdrant supports powerful filtering capabilities to narrow search results:

### Field Conditions

```python
from qdrant_client.http.models import Filter, FieldCondition, MatchValue

# Filter by category
filter = Filter(
    must=[
        FieldCondition(
            key="category",
            match=MatchValue(value="technology")
        )
    ]
)

results = client.search(
    collection_name="my_collection",
    query_vector=query_embedding,
    query_filter=filter,
    limit=10
)
```

### Range Filters

```python
from qdrant_client.http.models import Range

# Filter by date range
filter = Filter(
    must=[
        FieldCondition(
            key="timestamp",
            range=Range(gte=1609459200, lte=1640995200)
        )
    ]
)
```

## Best Practices

1. **Choose the right distance metric**: Cosine is generally best for text embeddings
2. **Index your payloads**: Create indexes on frequently filtered fields
3. **Batch operations**: Upsert points in batches for better performance
4. **Use appropriate vector size**: Match your embedding model's output dimension
5. **Monitor collection size**: Plan for storage based on vector count and dimension

## Integration Tips

- Use the official Python client `qdrant-client` for easy integration
- Implement retry logic for production reliability
- Consider using async methods for high-throughput applications
- Set appropriate timeouts based on your latency requirements

