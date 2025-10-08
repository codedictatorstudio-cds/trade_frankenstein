# Trade Frankenstein

![Trade Frankenstein Logo](/frontend/icons/icon.png)

## Overview

Trade Frankenstein is a sophisticated automated trading bot designed for Indian options trading (NIFTY & BANKNIFTY). Built with Spring Boot and Vaadin, it provides a comprehensive web-based dashboard for real-time monitoring, strategy execution, risk management, and performance analytics. The system integrates with Upstox broker API and employs advanced technical analysis, sentiment analysis, and machine learning for trading decisions.

## Key Features

### Trading & Execution
- **Automated Trading Engine**: Multi-strategy automated trade execution with configurable parameters
- **Upstox Broker Integration**: Real-time integration with Upstox API for order placement and market data
- **Options Trading**: Specialized support for NIFTY and BANKNIFTY options with dynamic strike selection
- **Multiple Execution Modes**: Sandbox and live trading modes with mock trading support
- **Order Management**: Complete order lifecycle management with retry and reconciliation mechanisms
- **Position Management**: Real-time position tracking with P&L calculation

### Strategy & Analysis
- **Technical Analysis (TA4J)**: Advanced technical indicators including:
  - EMA (Exponential Moving Average) - Fast (12) / Slow (26)
  - RSI (Relative Strength Index)
  - ADX (Average Directional Index) - Period 14
  - ATR (Average True Range) - Period 14
  - VWAP (Volume Weighted Average Price)
  - Donchian Channel - Window 20
  - SMA, Standard Deviation, and more
- **Market Regime Detection**: Automatic detection of trending, ranging, and volatile market conditions
- **Sentiment Analysis**: 
  - News sentiment analysis from live RSS feeds
  - Social media sentiment tracking
  - Market sentiment snapshots with historical tracking
- **Predictive Analytics**: Machine learning-based prediction service for trend forecasting
- **Multi-Strategy Support**: Includes Crossover, Mean Reversion, and Momentum strategies with individual risk caps

### Risk Management
- **Comprehensive Risk Engine**: Multi-layered risk validation including:
  - Per-strategy drawdown caps (configurable)
  - Position size limits
  - Delta exposure limits (NIFTY: 5000, BANKNIFTY: 5000)
  - Maximum lots per underlying (NIFTY: 30, BANKNIFTY: 20)
- **Circuit Breaker**: Automatic trading halt on breach of risk thresholds
- **Risk Snapshots**: Historical risk tracking with time-series storage
- **Throttling**: Rate limiting to prevent over-trading
- **Stop Loss & Take Profit**: Automated exit management with TTL (Time To Live)

### Real-time Features
- **Live Dashboard**: Responsive Vaadin-based UI with real-time updates
- **Server-Sent Events (SSE)**: Real-time data streaming for:
  - Market ticks
  - Trade executions
  - Advice signals
  - Risk alerts
  - Decision quality metrics
- **Event-Driven Architecture**: Kafka/Redpanda messaging for asynchronous processing
- **Fast State Store**: Redis-backed or in-memory high-speed state management
- **WebSocket Support**: Real-time market data streaming

### Data Management
- **MongoDB Time-Series**: Optimized storage for ticks and candles
  - Tick data retention: 30 days (2,592,000 seconds)
  - Candle data retention: 1 year (31,536,000 seconds)
- **Market Data Aggregation**: Multiple timeframes (5m, 15m, 1h) with automatic candle building
- **Data Quality Monitoring**: Real-time data quality events and alerts
- **Option Chain Management**: Complete option chain tracking and Greeks calculation
- **Historical Data**: Backtesting support with configurable lookback periods (up to 3 months)

### Analytics & Monitoring
- **Decision Quality Scoring**: Multi-factor scoring system combining:
  - Technical indicators
  - Sentiment analysis
  - News impact
  - Risk assessment
  - Portfolio state
- **Performance Metrics**: Comprehensive P&L tracking with daily and intraday rollups
- **Execution Logs**: Complete audit trail of all trading decisions
- **Alert System**: Configurable alerts for market events and risk conditions
- **Portfolio Analytics**: Real-time portfolio summary with Greeks and exposure analysis

### API & Integration
- **RESTful API**: Complete REST API for all trading operations
- **Swagger/OpenAPI**: Interactive API documentation at `/swagger-ui.html`
- **OAuth Integration**: Upstox OAuth 2.0 authentication flow
- **Resilience Patterns**: 
  - Circuit breaker (Resilience4j)
  - Retry with exponential backoff
  - Rate limiting
  - Bulkhead isolation
  - Time limiting

## Tech Stack

### Backend
- **Java**: 17 (LTS)
- **Spring Boot**: 3.5.5
- **Spring Data MongoDB**: Reactive and traditional repositories
- **Spring Data Redis**: Lettuce client for caching
- **Spring Kafka**: 3.9.0 for event streaming
- **Resilience4j**: 2.3.0 for fault tolerance

### Frontend
- **Vaadin**: 24.8.3 (Flow framework)
- **React**: 18.3.1
- **React Router**: 7.6.1
- **TypeScript**: 5.8.3
- **Vite**: 6.3.5 (build tool)
- **Lit**: 3.3.0 (web components)

### Data & Messaging
- **MongoDB**: Primary database with time-series collections
- **Redis**: High-speed caching and state management
- **Apache Kafka**: 3.9.0 (or Redpanda as alternative)

### Analysis & Trading
- **Upstox Java SDK**: 1.17
- **TA4J**: 0.17 (technical analysis library)
- **Apache Commons Math**: Statistical computations

### Development & Testing
- **Maven**: Build and dependency management
- **Lombok**: Code generation
- **Testcontainers**: 1.21.3 (MongoDB, Redis)
- **JUnit Jupiter**: Unit and integration testing
- **Mockito**: Mocking framework

### Logging & Monitoring
- **SLF4J**: 2.0.17
- **Logback**: 1.5.18
- **Logstash Encoder**: 7.4 (structured logging)
- **Micrometer**: Metrics collection
- **Spring Actuator**: Health checks and monitoring endpoints

### Infrastructure
- **Docker & Docker Compose**: Containerization
- **Kafka UI**: Web interface for Kafka (Provectus)
- **Zookeeper**: 3.9 (for Kafka coordination)

## Prerequisites

- **JDK 17+** (Oracle JDK or OpenJDK)
- **Maven 3.8+**
- **MongoDB 5.0+** (running locally or remote)
- **Redis 6.0+** (optional but recommended for production)
- **Docker & Docker Compose** (for Kafka/Redpanda setup)
- **Node.js 18+** and npm (for frontend development)
- **Upstox Trading Account** (for live trading)

## Quick Start

### 1. Clone the Repository

```bash
git clone <repository-url>
cd trade_frankenstein
```

### 2. Start Infrastructure Services

#### Option A: Using Kafka (Recommended for Production)

```bash
# Start Zookeeper, Kafka, and Kafka UI
docker-compose -f docker-compose.kafka.yml up -d

# Wait for services to be ready (30 seconds)
sleep 30

# Create required topics
chmod +x topics-kafka.sh
./topics-kafka.sh
```

The following services will be available:
- **Kafka Broker**: localhost:9092
- **Kafka UI**: http://localhost:9090
- **Zookeeper**: localhost:2181

#### Option B: Using Redpanda (Lightweight Alternative)

```bash
# Start Redpanda
docker-compose -f docker-compose.redpanda.yml up -d

# Wait for service to be ready
sleep 20

# Create required topics
chmod +x topics-redpanda.sh
./topics-redpanda.sh
```

Redpanda will be available at:
- **Kafka API**: localhost:9092
- **Schema Registry**: localhost:8081
- **Pandaproxy API**: localhost:8082
- **Admin API**: localhost:9644

### 3. Setup MongoDB

#### Local Installation

```bash
# Start MongoDB (macOS with Homebrew)
brew services start mongodb-community

# Or using Docker
docker run -d -p 27017:27017 --name mongodb mongo:latest
```

The application expects MongoDB at `mongodb://localhost:27017/TFS`.

### 4. Setup Redis (Optional but Recommended)

```bash
# Start Redis (macOS with Homebrew)
brew services start redis

# Or using Docker
docker run -d -p 6379:6379 --name redis redis:latest
```

To use in-memory state instead, set `trade.redis.enabled=false` in `application.properties`.

### 5. Configure Application

Edit `src/main/resources/application.properties`:

```properties
# Trading Mode (sandbox for testing, live for real trading)
trade.mode=sandbox

# Broker selection (mock for testing without real broker)
trade.broker=mock

# MongoDB connection
spring.data.mongodb.uri=mongodb://localhost:27017/TFS

# Redis configuration
trade.redis.enabled=true
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Kafka bootstrap servers
tf.kafka.bootstrap-servers=localhost:9092

# Allowed trading symbols
trade.symbols.allowed=NIFTY,BANKNIFTY

# Time zone (for Indian market)
trade.timezone=Asia/Kolkata
```

### 6. Build and Run

```bash
# Clean and install dependencies
mvn clean install

# Run the application
mvn spring-boot:run
```

Alternatively, build and run the JAR:

```bash
mvn clean package -DskipTests
java -jar target/tradefrankenstein-0.0.1-SNAPSHOT.jar
```

### 7. Access the Application

- **Dashboard**: http://localhost:8080/dashboard
- **Login Page**: http://localhost:8080/login
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/v3/api-docs
- **Health Check**: http://localhost:8080/actuator/health
- **Kafka UI** (if using Kafka): http://localhost:9090

## Configuration Deep Dive

### Application Properties

#### Server Configuration
```properties
server.port=8080
server.address=127.0.0.1  # Localhost only for security
spring.application.name=trade-frankenstein
```

#### Trading Configuration
```properties
# Trading mode: sandbox (paper trading) or live (real money)
trade.mode=${TRADE_MODE:sandbox}

# Broker: mock (simulated) or real broker name
trade.broker=mock

# Symbols allowed for trading
trade.symbols.allowed=NIFTY,BANKNIFTY

# Trade reconciliation interval (milliseconds)
trade.trades.reconcile-ms=45000

# Test mode (additional safety checks)
trade.test-mode.enabled=false
```

#### Risk Configuration
```properties
# Default drawdown cap (percentage)
risk.ddcap.default=3.0

# Maximum lots per underlying
risk.max.lots.NIFTY=30
risk.max.lots.BANKNIFTY=20

# Maximum delta exposure
risk.max.delta.NIFTY=5000
risk.max.delta.BANKNIFTY=5000

# Per-strategy drawdown caps
trading.risk.per-strategy.ddcap.CROSSOVER=2.5
trading.risk.per-strategy.ddcap.MEAN_REVERSION=1.5
trading.risk.per-strategy.ddcap.MOMENTUM=3.0
```

#### TA4J Technical Indicators
```properties
trade.ta4j.enabled=true
trade.ta4j.timeframes=5m,15m,1h
trade.ta4j.ema.fast=12
trade.ta4j.ema.slow=26
trade.ta4j.adx.period=14
trade.ta4j.atr.period=14
trade.ta4j.donchian.window=20
trade.ta4j.vwap.lookback=30
```

#### Resilience4j Configuration

**Retry Pattern**:
```properties
resilience4j.retry.instances.upstoxOrders.max-attempts=3
resilience4j.retry.instances.upstoxOrders.wait-duration=300
resilience4j.retry.instances.upstoxOrders.enable-exponential-backoff=true
resilience4j.retry.instances.upstoxOrders.exponential-backoff-multiplier=2
```

**Circuit Breaker**:
```properties
resilience4j.circuitbreaker.instances.upstoxOrders.sliding-window-size=20
resilience4j.circuitbreaker.instances.upstoxOrders.minimum-number-of-calls=10
resilience4j.circuitbreaker.instances.upstoxOrders.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.upstoxOrders.wait-duration-in-open-state=30
```

**Rate Limiter**:
```properties
resilience4j.ratelimiter.instances.upstoxOrders.limit-for-period=5
resilience4j.ratelimiter.instances.upstoxOrders.limit-refresh-period=1
```

#### MongoDB Time-Series
```properties
trade.mongo.timeseries.enabled=true
trade.mongo.timeseries.init=true
trade.mongo.timeseries.tick-expire-after-seconds=2592000  # 30 days
trade.mongo.timeseries.candle-expire-after-seconds=31536000  # 1 year
```

#### P&L Rollup Jobs
```properties
trade.pnl.rollup.enabled=true
trade.pnl.rollup.intraday-cron=0 0/1 9-15 ? * MON-FRI  # Every minute during market hours
trade.pnl.rollup.daily-cron=0 35 15 ? * MON-FRI  # At 3:35 PM daily
```

#### Kafka Configuration
```properties
tf.kafka.bootstrap-servers=localhost:9092
tf.sse.topics=advice,trade,risk,decision,audit,ticks,option_chain

# Producer settings
spring.kafka.producer.acks=1
spring.kafka.producer.batch-size=32768
spring.kafka.producer.linger-ms=5

# Consumer settings
spring.kafka.consumer.group-id=tf-trade-frankenstein
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.auto-offset-reset=latest
spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE
```

### Kafka Topics

The application uses the following Kafka topics:

| Topic | Partitions | Retention | Description |
|-------|-----------|-----------|-------------|
| `ticks` | 3 | 2 days | Real-time market tick data |
| `option_chain` | 3 | 1 day | Option chain updates |
| `advice` | 3 | 7 days | Trading advice/signals |
| `trade` | 3 | 30 days | Trade execution events |
| `risk` | 3 | 7 days | Risk alerts and events |
| `audit` | 3 | 30 days | Audit trail |
| `decision` | 3 | 7 days | Decision quality metrics |

## Architecture

### System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Frontend (Vaadin)                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │Dashboard │  │ Login    │  │ Charts   │  │  SSE     │   │
│  │  View    │  │  View    │  │ Sections │  │ Bridge   │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │ REST API / WebSocket
┌────────────────────────┴────────────────────────────────────┐
│                    Spring Boot Backend                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │            REST Controllers Layer                     │  │
│  │  AdviceController │ TradesController │ RiskController│  │
│  │  StrategyController │ PortfolioController │ etc.    │  │
│  └──────────────────────┬───────────────────────────────┘  │
│  ┌──────────────────────┴───────────────────────────────┐  │
│  │               Service Layer                           │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐           │  │
│  │  │ Engine   │  │Strategy  │  │Decision  │           │  │
│  │  │ Service  │  │ Service  │  │ Service  │           │  │
│  │  └──────────┘  └──────────┘  └──────────┘           │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐           │  │
│  │  │ Trades   │  │ Risk     │  │ Market   │           │  │
│  │  │ Service  │  │ Service  │  │ Data Svc │           │  │
│  │  └──────────┘  └──────────┘  └──────────┘           │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐           │  │
│  │  │Sentiment │  │ News     │  │Portfolio │           │  │
│  │  │ Service  │  │ Service  │  │ Service  │           │  │
│  │  └──────────┘  └──────────┘  └──────────┘           │  │
│  └──────────────────────┬───────────────────────────────┘  │
│  ┌──────────────────────┴───────────────────────────────┐  │
│  │          Integration Layer                            │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐           │  │
│  │  │ Upstox   │  │FastState │  │  Event   │           │  │
│  │  │ Service  │  │  Store   │  │Publisher │           │  │
│  │  └──────────┘  └──────────┘  └──────────┘           │  │
│  └──────────────────────┬───────────────────────────────┘  │
└─────────────────────────┼───────────────────────────────────┘
                          │
         ┌────────────────┼────────────────┐
         │                │                │
    ┌────▼────┐     ┌────▼────┐     ┌────▼────┐
    │ MongoDB │     │  Redis  │     │  Kafka  │
    │Time-    │     │ (State/ │     │/Redpanda│
    │ Series  │     │ Cache)  │     │(Events) │
    └─────────┘     └─────────┘     └─────────┘
                          │
                    ┌─────▼─────┐
                    │  Upstox   │
                    │  Broker   │
                    │    API    │
                    └───────────┘
```

### Component Breakdown

#### Core Services

1. **EngineService**: Orchestrates trading lifecycle, monitors positions, handles entry/exit logic
2. **StrategyService**: Implements trading strategies with technical indicator analysis
3. **DecisionService**: Multi-factor decision scoring and advice generation
4. **TradesService**: Trade execution, reconciliation, and lifecycle management
5. **RiskService**: Risk validation, circuit breaker, throttling, and limits enforcement
6. **MarketDataService**: Market data aggregation, regime detection, candle building
7. **PortfolioService**: Position tracking, P&L calculation, portfolio analytics
8. **UpstoxService**: Broker API integration with resilience patterns
9. **SentimentService**: Market sentiment analysis and scoring
10. **NewsService**: RSS feed parsing and news sentiment extraction
11. **OptionChainService**: Option chain management and Greeks calculation
12. **AdviceService**: Trade advice management and status tracking

#### Supporting Services

- **StreamGateway**: SSE streaming for real-time UI updates
- **EventPublisher**: Kafka event publishing
- **FastStateStore**: Redis/in-memory high-speed state management
- **AlertService**: Alert generation and management
- **MetricsCollector**: Performance metrics collection
- **PredictionService**: ML-based prediction and forecasting

#### Scheduled Jobs

- **Market Data Polling**: Continuous tick and candle updates
- **Trade Reconciliation**: Periodic sync with broker (every 45 seconds)
- **P&L Rollup**: Intraday (every minute) and daily (3:35 PM) aggregation
- **Risk Monitoring**: Continuous risk assessment
- **News Fetching**: Periodic RSS feed updates
- **Session Token Refresh**: Upstox token renewal

## Data Model

### Core Collections (MongoDB)

#### Trading Documents
- **trades**: Executed trades with entry/exit prices, P&L
- **orders**: Order requests and broker responses
- **advices**: Trading signals with confidence scores
- **positions**: Current open positions

#### Market Data (Time-Series)
- **ticks**: Real-time tick data with timestamp-based indexing
- **candles_1m**: 1-minute OHLCV candles
- **ltp_quotes**: Last traded price snapshots
- **ohlc_quotes**: OHLC quote snapshots
- **option_instruments**: Option contract details

#### Risk & Analytics
- **risk_events**: Risk violations and circuit breaker events
- **risk_snapshots**: Point-in-time risk state
- **decision_quality**: Decision scoring history
- **execution_logs**: Detailed execution audit trail
- **portfolio_snapshots**: Historical portfolio state

#### Reference Data
- **trading_days**: Market calendar and holidays
- **exchange_holidays**: Exchange-specific holidays
- **market_sentiment_snapshots**: Aggregated sentiment data
- **market_signals**: Technical signal events
- **alerts**: System and trading alerts

#### Quality & Monitoring
- **data_quality_events**: Data quality issues
- **metrics**: System performance metrics
- **circuit_breaker**: Circuit breaker state

### Entity Relationships

```
Trade
  ├─ instrumentSymbol (String)
  ├─ entryPrice (BigDecimal)
  ├─ exitPrice (BigDecimal)
  ├─ quantity (Integer)
  ├─ status (TradeStatus: OPEN, CLOSED, CANCELLED)
  ├─ realizedPnl (BigDecimal)
  └─ adviceId (Reference to Advice)

Advice
  ├─ instrumentSymbol (String)
  ├─ side (OrderSide: BUY, SELL)
  ├─ confidence (Integer 0-100)
  ├─ techScore (Integer)
  ├─ newsScore (Integer)
  ├─ status (AdviceStatus: PENDING, EXECUTED, REJECTED, EXPIRED)
  ├─ strategy (StrategyName: CROSSOVER, MEAN_REVERSION, MOMENTUM)
  └─ orderId (Reference to Order)

Order
  ├─ brokerOrderId (String)
  ├─ instrumentSymbol (String)
  ├─ side (OrderSide)
  ├─ type (OrderType: MARKET, LIMIT)
  ├─ price (BigDecimal)
  ├─ quantity (Integer)
  └─ status (OrderStatus: PENDING, PLACED, FILLED, REJECTED, CANCELLED)

Position
  ├─ instrumentSymbol (String)
  ├─ quantity (Integer)
  ├─ avgPrice (BigDecimal)
  ├─ currentPrice (BigDecimal)
  └─ unrealizedPnl (BigDecimal)
```

## API Reference

### REST Endpoints

#### Trading Operations

**Engine Control**
- `POST /api/engine/start` - Start trading engine
- `POST /api/engine/stop` - Stop trading engine
- `GET /api/engine/status` - Get engine status

**Advice Management**
- `GET /api/advice` - List all advice (paginated)
- `GET /api/advice/{id}` - Get specific advice
- `POST /api/advice/execute/{id}` - Execute an advice
- `DELETE /api/advice/{id}` - Cancel an advice

**Trade Management**
- `GET /api/trades` - List all trades (paginated)
- `GET /api/trades/{id}` - Get specific trade
- `POST /api/trades/reconcile` - Trigger trade reconciliation
- `GET /api/trades/pnl` - Get P&L summary

**Order Management**
- `GET /api/orders` - List all orders
- `GET /api/orders/{id}` - Get specific order
- `POST /api/orders/place` - Place new order
- `PUT /api/orders/{id}` - Modify order
- `DELETE /api/orders/{id}` - Cancel order

**Risk Management**
- `GET /api/risk/status` - Get current risk status
- `GET /api/risk/limits` - Get risk limits
- `POST /api/risk/circuit-breaker/reset` - Reset circuit breaker
- `GET /api/risk/snapshots` - Get risk history

#### Market Data

**Real-time Data**
- `GET /api/market/ltp/{symbol}` - Get last traded price
- `GET /api/market/candles/{symbol}` - Get historical candles
- `GET /api/market/regime` - Get current market regime
- `GET /api/market/ticks` - Stream real-time ticks (SSE)

**Option Chain**
- `GET /api/option-chain/{underlying}` - Get option chain
- `GET /api/option-chain/strikes/{underlying}` - Get available strikes
- `GET /api/option-chain/greeks/{symbol}` - Get option Greeks

**Sentiment & News**
- `GET /api/sentiment/current` - Get current market sentiment
- `GET /api/sentiment/history` - Get sentiment history
- `GET /api/news/latest` - Get latest news items
- `GET /api/news/sentiment` - Get news-based sentiment

#### Portfolio & Analytics

**Portfolio**
- `GET /api/portfolio/summary` - Get portfolio summary
- `GET /api/portfolio/positions` - Get all positions
- `GET /api/portfolio/holdings` - Get holdings
- `GET /api/portfolio/greeks` - Get aggregate Greeks

**Decision Quality**
- `GET /api/decision/quality` - Get current decision quality score
- `GET /api/decision/history` - Get decision history

**Strategy**
- `GET /api/strategy/indicators/{symbol}` - Get technical indicators
- `GET /api/strategy/signals/{symbol}` - Get strategy signals

#### Server-Sent Events (SSE)

- `GET /api/stream/subscribe` - Subscribe to all topics
- `GET /api/stream/advice` - Subscribe to advice stream
- `GET /api/stream/trades` - Subscribe to trade stream
- `GET /api/stream/risk` - Subscribe to risk alerts
- `GET /api/stream/ticks` - Subscribe to market ticks
- `GET /api/stream/decision` - Subscribe to decision updates

### Authentication

The application uses Upstox OAuth 2.0 for broker authentication:

1. Navigate to `/login`
2. Click "Login with Upstox"
3. Authorize the application
4. Redirected back with access token

OAuth endpoints:
- `GET /oauth/upstox/authorize` - Initiate OAuth flow
- `GET /oauth/upstox/callback` - OAuth callback handler

## Development

### Project Structure

```
trade_frankenstein/
├── src/main/java/com/trade/frankenstein/trader/
│   ├── TradefrankensteinApplication.java    # Main application
│   ├── bus/                                  # Event publishing (Kafka)
│   ├── common/                               # Shared utilities, constants
│   ├── config/                               # Spring configuration
│   ├── core/                                 # Core abstractions (FastStateStore)
│   ├── dto/                                  # Data Transfer Objects
│   ├── enums/                                # Enumerations
│   ├── jobs/                                 # Scheduled jobs
│   ├── model/                                # Data models
│   │   ├── documents/                        # MongoDB documents
│   │   └── upstox/                          # Upstox API models
│   ├── oauth/                                # OAuth controllers
│   ├── repo/                                 # Spring Data repositories
│   ├── service/                              # Business logic
│   │   ├── advice/                          # Advice services
│   │   ├── decision/                        # Decision services
│   │   ├── market/                          # Market data services
│   │   ├── news/                            # News services
│   │   ├── risk/                            # Risk services
│   │   └── sentiment/                       # Sentiment services
│   ├── ui/                                   # Vaadin UI components
│   │   ├── bridge/                          # UI-Backend bridge
│   │   ├── header/                          # Header components
│   │   ├── sections/                        # Dashboard sections
│   │   └── shared/                          # Shared UI components
│   └── web/                                  # REST controllers
├── src/main/resources/
│   ├── application.properties               # Main configuration
│   ├── consumer.properties                  # Kafka consumer config
│   ├── producer.properties                  # Kafka producer config
│   └── logback-spring.xml                   # Logging configuration
├── src/test/                                 # Tests
├── frontend/                                 # Frontend resources
│   ├── generated/                           # Auto-generated Vaadin files
│   ├── styles/                              # CSS stylesheets
│   └── themes/                              # Vaadin themes
├── data/                                     # Application data
├── docker-compose.kafka.yml                 # Kafka setup
├── docker-compose.redpanda.yml              # Redpanda setup
├── topics-kafka.sh                          # Kafka topic creation
├── topics-redpanda.sh                       # Redpanda topic creation
├── pom.xml                                  # Maven configuration
├── package.json                             # NPM configuration
└── tsconfig.json                            # TypeScript configuration
```

### Frontend Development

The project uses Vaadin with React components and TypeScript.

**Start development server**:
```bash
npm install
npm run dev
```

**Build for production**:
```bash
mvn clean package -Pproduction
```

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=RiskServiceTest

# Run integration tests (uses Testcontainers)
mvn verify
```

Integration tests automatically start MongoDB and Redis containers.

### Code Style

The project uses:
- **Lombok** for reducing boilerplate
- **SLF4J** for logging
- **Java 17** features (records, text blocks, etc.)
- **Spring Boot conventions** for structure

### Adding New Strategies

1. Define strategy in `StrategyName` enum
2. Implement logic in `StrategyService`
3. Configure risk limits in `application.properties`
4. Add tests in `src/test/java/.../test/service`

Example:
```java
// In StrategyName.java
public enum StrategyName {
    CROSSOVER,
    MEAN_REVERSION,
    MOMENTUM,
    YOUR_NEW_STRATEGY  // Add here
}
```

```properties
# In application.properties
trading.risk.per-strategy.ddcap.YOUR_NEW_STRATEGY=2.0
```

## Deployment

### Environment Variables

Key environment variables for deployment:

```bash
# Trading mode
export TRADE_MODE=sandbox  # or 'live'

# MongoDB
export SPRING_DATA_MONGODB_URI=mongodb://user:pass@host:27017/TFS

# Redis
export SPRING_DATA_REDIS_HOST=redis-host
export SPRING_DATA_REDIS_PORT=6379

# Kafka
export TF_KAFKA_BOOTSTRAP_SERVERS=kafka:9092

# Upstox credentials (for OAuth)
export UPSTOX_API_KEY=your-api-key
export UPSTOX_API_SECRET=your-api-secret
export UPSTOX_REDIRECT_URI=http://localhost:8080/oauth/upstox/callback

# Server
export SERVER_PORT=8080
```

### Docker Deployment

Build Docker image:
```bash
# Build application JAR
mvn clean package -DskipTests

# Create Dockerfile (example)
cat > Dockerfile <<EOF
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/tradefrankenstein-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF

# Build image
docker build -t trade-frankenstein:latest .

# Run container
docker run -d -p 8080:8080 \
  -e TRADE_MODE=sandbox \
  -e SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/TFS \
  -e TF_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  --name trade-frankenstein \
  trade-frankenstein:latest
```

### Production Checklist

- [ ] Set `trade.mode=live` for real trading
- [ ] Configure real broker credentials
- [ ] Enable Redis (`trade.redis.enabled=true`)
- [ ] Set appropriate risk limits
- [ ] Configure production MongoDB with replica set
- [ ] Set up Kafka cluster (not single node)
- [ ] Enable SSL/TLS for external connections
- [ ] Configure proper logging levels
- [ ] Set up monitoring and alerts
- [ ] Enable backups for MongoDB
- [ ] Review and adjust resilience4j settings
- [ ] Test circuit breaker and failover
- [ ] Verify all scheduled jobs are working
- [ ] Test OAuth flow with production URLs

## Monitoring & Operations

### Health Checks

```bash
# Application health
curl http://localhost:8080/actuator/health

# Detailed health (includes MongoDB, Redis, Kafka)
curl http://localhost:8080/actuator/health/details
```

### Metrics

```bash
# All metrics
curl http://localhost:8080/actuator/metrics

# Specific metric (example: JVM memory)
curl http://localhost:8080/actuator/metrics/jvm.memory.used
```

### Logging

Logs are written to:
- **Console**: Colored output with timestamps
- **File** (if configured): Structured JSON format via Logstash encoder

Log levels can be adjusted:
```properties
logging.level.root=INFO
logging.level.com.trade.frankenstein=DEBUG
logging.level.io.github.resilience4j=INFO
```

### Common Operations

**Check Kafka topics**:
```bash
# Using Kafka UI
Open http://localhost:9090

# Using CLI
docker exec kafka-broker kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

**Monitor MongoDB**:
```bash
# Connect to MongoDB
mongosh mongodb://localhost:27017/TFS

# Check collections
show collections

# Check tick data
db.ticks.find().limit(5)

# Check time-series stats
db.ticks.stats()
```

**Check Redis**:
```bash
# Connect to Redis
redis-cli

# Check keys
KEYS *

# Monitor commands
MONITOR
```

## Troubleshooting

### Common Issues

**1. Application won't start - MongoDB connection error**
```bash
# Verify MongoDB is running
mongosh mongodb://localhost:27017/TFS

# Check connection string in application.properties
spring.data.mongodb.uri=mongodb://localhost:27017/TFS
```

**2. Kafka connection errors**
```bash
# Verify Kafka is running
docker ps | grep kafka

# Test connection
docker exec kafka-broker kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092
```

**3. Redis connection errors**
```bash
# Disable Redis if not available
trade.redis.enabled=false

# Or start Redis
docker run -d -p 6379:6379 redis:latest
```

**4. Upstox API errors**
- Check API key and secret are correct
- Verify OAuth callback URL matches registered URL
- Check rate limits aren't exceeded
- Review Resilience4j circuit breaker state

**5. Trading engine not starting**
- Ensure user is logged in (OAuth complete)
- Check risk limits aren't already breached
- Verify market hours (9:15 AM - 3:30 PM IST)
- Check trading mode is set correctly

## Performance Tuning

### Kafka Optimization
```properties
# Increase batch size for higher throughput
spring.kafka.producer.batch-size=65536
spring.kafka.producer.linger-ms=10

# Adjust consumer concurrency
spring.kafka.listener.concurrency=3
```

### MongoDB Optimization
```properties
# Enable indexes on frequently queried fields
# Already configured in @Document classes

# Adjust time-series retention
trade.mongo.timeseries.tick-expire-after-seconds=1296000  # 15 days instead of 30
```

### Redis Optimization
```properties
# Adjust timeout for faster failover
spring.data.redis.timeout=1000

# Use connection pooling
spring.data.redis.lettuce.pool.max-active=20
spring.data.redis.lettuce.pool.max-idle=10
```

### JVM Tuning
```bash
# Increase heap size for production
java -Xms2g -Xmx4g -jar target/tradefrankenstein-0.0.1-SNAPSHOT.jar

# Enable G1GC (recommended for low-latency)
java -XX:+UseG1GC -jar target/tradefrankenstein-0.0.1-SNAPSHOT.jar
```

## Security Considerations

### API Security
- Application binds to `127.0.0.1` (localhost only)
- Use reverse proxy (nginx) for external access
- Enable HTTPS in production
- Implement rate limiting

### Credential Management
- Never commit API keys to repository
- Use environment variables or secrets management
- Rotate credentials regularly
- Use separate credentials for sandbox and live

### Trading Safety
- Always test in sandbox mode first
- Set conservative risk limits initially
- Monitor circuit breaker events
- Keep emergency stop mechanism accessible

## Roadmap

### Planned Features
- [ ] Additional broker support (Zerodha, Angel One)
- [ ] Advanced ML models for prediction
- [ ] Backtesting framework with historical data
- [ ] Mobile app for monitoring
- [ ] Multi-user support with role-based access
- [ ] Advanced charting with TradingView integration
- [ ] Telegram/WhatsApp notifications
- [ ] Strategy marketplace
- [ ] Cloud deployment templates (AWS, GCP, Azure)

## License

This project is proprietary and for personal use only. Unauthorized distribution or commercial use is prohibited.

## Disclaimer

**IMPORTANT**: This software is for educational and personal use only. Trading involves substantial risk of loss. Use at your own risk. The authors are not responsible for any financial losses incurred through the use of this software.

- Always test thoroughly in sandbox mode
- Start with small position sizes
- Never risk more than you can afford to lose
- Past performance does not guarantee future results
- Consult with a financial advisor before live trading

## Support & Contributing

This is a personal project. For issues or suggestions:
1. Check existing documentation
2. Review closed issues for similar problems
3. Open a new issue with detailed description

## Acknowledgements

- **Spring Boot Team** - Excellent framework and documentation
- **Vaadin Team** - Modern Java web framework
- **Upstox** - Broker API and SDK
- **TA4J Community** - Technical analysis library
- **Apache Kafka** - Event streaming platform
- **MongoDB Team** - Time-series database capabilities
- **Resilience4j** - Fault tolerance library

---

**Built with ❤️ for algorithmic trading in Indian markets**

*Last Updated: October 2025*
