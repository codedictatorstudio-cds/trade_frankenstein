# Trade Frankenstein

![Trade Frankenstein Logo](/frontend/icons/icon.png)

## Overview

Trade Frankenstein is an automated trading bot designed to execute trades based on configurable strategies. Built with Spring Boot and Vaadin, it offers a complete web-based dashboard for monitoring trades, managing strategies, and analyzing performance.

## Features

- **Automated Trading**: Configure and deploy strategies for automatic trade execution
- **Multiple Broker Support**: Support for mock trading and real brokerages
- **Real-time Monitoring**: Track positions and performance via a responsive dashboard
- **Strategy Backtesting**: Test strategies against historical data
- **Trade Reconciliation**: Automatic reconciliation of trades with broker data
- **Swagger API Documentation**: Full API documentation for integration
- **Feature Flags**: Toggle features on/off for phased deployment

## Tech Stack

- **Backend**: Java 17, Spring Boot 3.5.5
- **Frontend**: Vaadin 24.8.3, React, TypeScript
- **Database**: MongoDB
- **Messaging**: Apache Kafka/Redpanda
- **Documentation**: OpenAPI/Swagger
- **Build Tool**: Maven

## Prerequisites

- JDK 17+
- MongoDB
- Docker and Docker Compose (for Kafka/Redpanda setup)
- Node.js and npm (for frontend development)

## Quick Start

### 1. Clone the Repository

```bash
git clone <repository-url>
cd trade_frankenstein
```

### 2. Start the Infrastructure Services

Choose one of the following options to set up the messaging infrastructure:

**Option 1: Kafka**
```bash
docker-compose -f docker-compose.kafka.yml up -d
```

**Option 2: Redpanda** 
```bash
docker-compose -f docker-compose.redpanda.yml up -d
```

### 3. Create Required Topics

After starting the messaging infrastructure, create the required topics using the provided scripts:

**For Kafka:**
```bash
./topics-kafka.sh
```

**For Redpanda:**
```bash
./topics-redpanda.sh
```

### 4. Configure MongoDB

Ensure MongoDB is running locally on default port (27017) or update the connection details in `application.properties`.

### 5. Build and Run

```bash
mvn clean install
mvn spring-boot:run
```

### 6. Access the Application

- **Dashboard**: [http://localhost:8080](http://localhost:8080)
- **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Kafka UI** (if using Kafka): [http://localhost:9090](http://localhost:9090)

## Configuration

### Core Configuration

The main application configuration is in `src/main/resources/application.properties`. Key configurations include:

```properties
# Trading mode: sandbox or live
trade.mode=sandbox

# Broker selection
trade.broker=mock

# Allowed trading symbols
trade.symbols.allowed=NIFTY,BANKNIFTY

# Trade reconciliation interval (ms)
trade.trades.reconcile-ms=45000
```

### Feature Flags

Feature flags are managed in `data/feature-flags.properties` and allow for controlled feature rollout.

## Development

### Frontend Development

The project uses Vaadin with TypeScript and React components. The main frontend code is in the `frontend` directory.

To start the frontend development server:

```bash
npm run dev
```

### API Documentation

The application includes Swagger UI for API documentation and testing, available at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

## Architecture

The application follows a modular architecture:

1. **Strategy Module**: Defines and executes trading strategies
2. **Trader Module**: Handles order execution and trade management
3. **Broker Integration**: Adapters for different brokerages
4. **Analytics Engine**: Analyzes market data and generates trading signals
5. **Dashboard**: Web interface for monitoring and configuration

## Data Model

The core data model includes:

- **Trades**: Records of executed trades
- **Orders**: Buy/sell instructions sent to brokers
- **Instruments**: Securities available for trading
- **Advice**: Trade suggestions from analysis or signals

## Deployment

The application is designed to run on any environment supporting Java 17 and can be deployed as:

1. **Standalone application**: Using the Spring Boot JAR
2. **Docker container**: Using the provided Dockerfile
3. **Kubernetes**: Using the provided deployment manifests

## License

This project is proprietary and for personal use only.

## Acknowledgements

- Spring Boot and Vaadin communities
- Apache Kafka and MongoDB communities
