# Building a Bluesky Bot with Kotlin Notebooks and Redis

## Project Overview

This project teaches you how to build a Bluesky social network bot using Kotlin Notebooks and Redis as the core data platform. The bot can analyze posts from the Bluesky network, identify trending topics, and respond to user queries about the content. The project demonstrates how to use Redis and its powerful data structures alongside vector search and Large Language Models (LLMs) to build an intelligent data processing pipeline. Redis serves as the backbone of the entire application, providing real-time data storage, filtering, and retrieval capabilities.

## What You'll Build

Throughout this project, you'll build a complete bot that:

1. Consumes real-time events from Bluesky's Jetstream Websocket and stores them in Redis Streams
2. Filters and processes these events using AI and Redis Bloom Filter for deduplication
3. Enriches the data with topic modeling and vector embeddings stored in Redis
4. Analyzes the data to identify trends and answer questions using Redis Search and vector similarity
5. Interacts with users on the Bluesky platform based on data processed through Redis

## Learning Path

The project is structured as a series of Kotlin Notebooks that guide you through the process step by step:

### 1. JetStream Consumer

In this notebook, you'll learn how to:
- Connect to Bluesky's Jetstream Websocket to receive real-time events
- Model the event data structure
- Store events in Redis Streams for reliable, persistent event processing
- Configure Redis connection pools for optimal performance

### 2. JetStream Filtering

Building on the first notebook, you'll learn how to:
- Implement efficient deduplication using Redis Bloom Filter
- Create Redis consumer groups for reliable event processing
- Perform content-based filtering using a zero-shot classification model
- Store filtered events in Redis hashes for further processing
- Create a Redis-powered pipeline pattern for processing events

### 3. Events Enrichment

With filtered events in hand, you'll learn how to:
- Extract topics from posts using a Large Language Model
- Track topic frequency with Redis Count-Min Sketch
- Create vector embeddings for semantic search
- Store embeddings in Redis for fast retrieval
- Build a Redis Search index with vector capabilities for efficient querying
- Implement different types of Redis searches (exact matching, full-text, vector similarity)

### 4. Data Analysis with AI

Taking the enriched data, you'll learn how to:
- Identify trending topics in posts using Redis analytics
- Implement semantic routing with Redis vector search
- Create a question-answering system using Redis vector store and LLMs
- Generate natural language responses based on Redis-stored data
- Perform complex queries combining Redis Search with vector similarity

### 5. Building the Bot

Finally, you'll bring everything together by:
- Setting up authentication with the Bluesky API
- Implementing post search and creation using Redis-stored data
- Creating a bot that responds to user mentions based on Redis-powered analytics
- Handling long responses by splitting them into chunks
- Leveraging Redis for caching authentication tokens and user interactions

## Redis as the Core Technology

Redis plays a central role in this project, serving as the backbone for the entire data processing pipeline. Here's how different Redis features are utilized:

### Redis Streams
- Acts as a message broker for real-time event processing
- Enables reliable consumption of events with consumer groups
- Provides persistence for the event flow

### Redis Bloom Filter
- Efficiently handles deduplication of events
- Prevents processing the same post multiple times
- Reduces memory usage compared to traditional set-based approaches

### Redis Query Engine
- Powers full-text search capabilities for post content
- Enables vector similarity search for semantic understanding
- Supports hybrid queries combining exact matching with vector similarity

### Redis Data Structures
- Hash maps for storing post metadata and content
- Sets for managing topics and categories
- Bloom filters for deduplication
- Count-Min Sketch for tracking trending topics with minimal memory usage

By leveraging these Redis capabilities, the application achieves high performance, scalability, and real-time processing while maintaining a simple architecture.

## Standalone Applications

The project also includes standalone application modules that implement the functionality taught in the notebooks:

### Consumer App

The `consumer-app` module implements the JetStream Consumer functionality from the first notebook. It:
- Connects to Bluesky's Jetstream Websocket
- Processes events in real-time
- Stores them in Redis Streams using optimized Redis connection pools
- Leverages Redis's persistence capabilities for reliable event storage

### Filtering App

The `filtering-app` module implements the JetStream Filtering functionality from the second notebook. It:
- Reads events from Redis Streams using consumer groups for reliable processing
- Filters them using a zero-shot classification model
- Deduplicates events using Redis Bloom Filter for memory-efficient uniqueness checking
- Stores filtered events in Redis hashes for structured data access
- Creates a new Redis Stream of filtered events for downstream processing

### Topic Extraction App

The `topic-extraction-app` module implements the topic modeling functionality from the third notebook. It:
- Reads filtered events from Redis Streams using consumer groups
- Extracts topics using a Large Language Model
- Stores topics in Redis sets for efficient membership testing
- Counts topic occurrences using Redis Count-Min Sketch for space-efficient frequency tracking
- Updates Redis Search indexes for real-time searchability of enriched content

## Running the Applications

You can run the applications in two ways:

1. **Interactive Learning**: Work through the Kotlin Notebooks in sequence to understand each component and see the results in real-time.

2. **Standalone Applications**: Run the individual application modules to implement the complete pipeline as separate services.

To run a standalone application, navigate to its directory and use:

```bash
./gradlew run
```

## Prerequisites

To work with this project, you'll need:

- Basic knowledge of Kotlin
- Docker for running Redis Stack (which includes Redis Search and other modules)
- An Ollama installation for running LLMs locally (or OpenAI API key)
- A Bluesky account for testing the bot

## Getting Started

1. Clone the repository
2. Start Redis Stack using Docker: `docker run -p 6379:6379 redis/redis-stack`
   - This provides Redis with all the necessary modules (Redis Search, RedisJSON, etc.)
   - Redis Stack also includes RedisInsight for visualizing your data
3. Verify Redis is running: `redis-cli ping` (should return "PONG")
4.	Start Ollama with the Deepseek Coder model: `ollama run deepseek-coder-v2`
5. Open the Kotlin Notebooks in your preferred environment (IntelliJ IDEA recommended)
6. Follow the notebooks in sequence to build your Redis-powered bot

Happy coding!
