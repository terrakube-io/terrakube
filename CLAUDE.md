# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Frontend (React/TypeScript - UI)
```bash
cd ui/
npm start         # Start development server (Vite, port 3000)
yarn dev          # Alternative development server
npm run build     # Build for production
npm test          # Run Jest tests
npm run test:coverage  # Test coverage report
npm run lint      # Check ESLint rules
npm run lint:modules   # Lint only modules directory
npm run format    # Format code with Prettier
npm run format:modules # Format only modules directory
```

### Backend (Java/Spring Boot 3.5.4)
```bash
# Build all modules
mvn clean install

# Run services
cd api/
mvn spring-boot:run    # API service (port 8080)

cd registry/
mvn spring-boot:run    # Registry service (port 8075)

cd executor/
mvn spring-boot:run    # Executor service (port 8090)

# Testing
mvn test              # Run all tests
mvn verify            # Run tests + additional verification
mvn test -Dspring.profiles.active=test  # API tests with test profile

# Build & Analysis
mvn spring-boot:build-image  # Build Docker images with buildpacks
mvn -P sonar              # Run SonarCloud analysis (if configured)
```

### Development Environment Setup
```bash
# Start complete development environment (Gitpod)
./scripts/setupDevelopmentEnvironment.sh

# For specific cloud providers
./scripts/setupDevelopmentEnvironmentAzure.sh
./scripts/setupDevelopmentEnvironmentGcp.sh
./scripts/setupDevelopmentEnvironmentMinio.sh

# Docker Compose development environment
cd docker-compose/
docker-compose up -d  # Full stack with PostgreSQL, Redis, MinIO, Traefik
```

### DevContainer Support
VS Code devcontainer configured with Java 17, Node 20, Terraform, and all required tools.

## Architecture Overview

### Multi-Service Architecture
Terrakube is a microservices application for Infrastructure as Code (IaC) automation supporting both Terraform and OpenTofu:

- **API Service** (port 8080): Core business logic, data management, Terraform Cloud API compatibility
- **Registry Service** (port 8075): Terraform/OpenTofu module and provider registry
- **Executor Service** (port 8090): Job execution engine for running Terraform operations
- **UI Service** (port 3000): React frontend with Ant Design components

### Backend (Java/Spring Boot)

#### Key Java Packages Structure
```
io.terrakube.api/
├── rs/                    # JPA entities (Organization, Workspace, Job, etc.)
├── repository/            # Spring Data JPA repositories  
├── plugin/
│   ├── scheduler/         # Quartz job scheduling
│   ├── security/          # Authentication & authorization
│   ├── storage/           # Multi-cloud storage (AWS/Azure/GCP)
│   ├── vcs/              # Git provider integrations
│   └── state/            # Terraform state management
└── hooks/                 # Lifecycle event handlers
```

#### Core Domain Entities
- **Organization**: Root tenant entity containing workspaces, teams, modules
- **Workspace**: Main working unit linked to VCS repos, supports variables and job history
- **Job**: Execution unit for plan/apply/destroy operations with approval workflows
- **Team**: User groups with role-based permissions using expression language
- **Module/Provider**: Terraform registry entries with version management

#### Authentication & Authorization
- Primary auth via **Dex** (OpenID Connect) integration
- Personal Access Tokens (PAT) for CLI/API access
- Expression-based permissions: `"team view workspace OR team limited view workspace"`
- Multi-tenant with organization-level isolation

#### API Endpoints
- **JSON:API**: Primary API at `/api/v1` using Elide framework with GraphQL at `/graphql/api/v1`
- **Terraform Cloud API**: Compatibility layer at `/remote/tfe/v2/`
- **Registry API**: Terraform module/provider protocol
- **State API**: Terraform state file operations at `/tfstate/v1/`

### Frontend (React/TypeScript)

#### Component Organization
```
src/
├── domain/               # Feature-based domains
│   ├── Workspaces/      # Workspace management UI
│   ├── Organizations/   # Organization settings
│   ├── Settings/        # System configuration  
│   └── Jobs/           # Job execution monitoring
├── modules/             # Modern modular architecture
│   ├── api/            # API communication layer
│   ├── workspaces/     # Workspace utilities
│   └── organizations/  # Organization services
└── config/             # App configuration
```

#### Key Patterns
- **State Management**: React Context + minimal Redux, localStorage persistence
- **API Integration**: Axios with `useApiRequest` custom hook, typed responses
- **Routing**: React Router v7 with nested routes (`/organizations/:orgid/workspaces/:id`)
- **Design System**: Ant Design with custom theming (light/dark modes)
- **Authentication**: OIDC integration with `react-oidc-context`

#### Environment Configuration
- Runtime configuration via `env.sh` script generating `env-config.js`
- Access via `window._env_` global object
- Key vars: `REACT_APP_TERRAKUBE_API_URL`, `REACT_APP_AUTHORITY`, `REACT_APP_CLIENT_ID`
- Multiple deployment environment files: `.envApi`, `.envRegistry`, `.envExecutor`, `.env` (Docker Compose)

## Database & Storage

### Database Schema
- **Liquibase migrations**: Located in `api/src/main/resources/db/changelog/`
- **Supported databases**: PostgreSQL, SQL Server, H2 (testing)
- **Multi-cloud storage**: AWS S3, Azure Blob Storage, GCP Cloud Storage
- **State management**: Encrypted cloud-native backends

### Development Data
- Demo organizations loaded via XML: `api/src/main/resources/db/changelog/demo-data/`
- Test users: admin@example.com/admin, aws@example.com/aws, etc.
- Sample modules from GitHub repositories

## Testing

### Backend Testing
```bash
# Run all tests
mvn test

# API-specific tests with profiles
cd api/
mvn test -Dspring.profiles.active=test
```

### Frontend Testing  
```bash
cd ui/
npm test                    # Run Jest tests
npm run test:coverage      # Test coverage report
```

### Integration Testing
- Thunder Client collection included for API testing
- Postman collections in `postman/` directory
- Device code authentication flow for testing

## CI/CD & Quality Assurance

### GitHub Actions Workflows
```bash
# Automated workflows trigger on PRs and releases:
# - Backend: Java 17, SonarCloud analysis, multi-platform builds (Jammy, Alpaquita Linux)
# - Frontend: Node 22, Yarn, ESLint validation
# - Release: Multi-platform Docker builds, automated versioning
```

### Code Quality
- SonarCloud integration for static analysis
- ESLint + Prettier for frontend code formatting
- Maven Surefire for backend test execution
- Jest for frontend unit testing

## Key Configuration Files

### Backend Configuration
- `api/src/main/resources/application.properties`: Main API configuration
- `registry/src/main/resources/application.properties`: Registry settings  
- `executor/src/main/resources/application.properties`: Executor settings

### Frontend Configuration
- `ui/package.json`: Dependencies and scripts (React 18.3.1, Vite)
- `ui/vite.config.mts`: Build configuration (dev server port 3000)
- `ui/tsconfig.*.json`: TypeScript compilation settings
- `ui/env.sh`: Runtime environment configuration generator

### Docker & Deployment
- `docker-compose/docker-compose.yml`: Complete stack with Traefik, PostgreSQL, Redis, MinIO
- `ui/Dockerfile`: Frontend container configuration
- `.devcontainer/devcontainer.json`: VS Code development environment

## Development Workflow

### Making Changes
1. **Backend changes**: Modify Java code in respective service modules
2. **Frontend changes**: Update React components in `ui/src/`
3. **Database changes**: Add Liquibase changesets to `api/src/main/resources/db/changelog/`
4. **API changes**: Update both backend controllers and frontend API integration

### Common Development Tasks

#### Adding New Terraform Providers
1. Update provider tables in database via Liquibase
2. Add provider validation in Registry service
3. Update UI provider display components

#### Adding New VCS Integrations
1. Implement VCS plugin in `api/plugin/vcs/`
2. Add configuration options to Organization entity
3. Update UI VCS configuration forms

#### Extending Job Templates
1. Add template definitions in Settings
2. Implement template logic in Executor service
3. Update UI template management components

## Architecture Patterns

- **Multi-tenancy**: Organization-based tenant isolation with expression-based permissions
- **Event-driven**: Lifecycle hooks and webhook integrations for VCS
- **Plugin architecture**: Extensible storage, auth, and VCS providers
- **API compatibility**: Terraform Cloud API surface for seamless tool integration
- **Async processing**: Quartz scheduler for background jobs and VCS polling

This architecture supports enterprise-grade infrastructure automation with comprehensive Terraform and OpenTofu workflow management, multi-cloud deployments, and extensive integration capabilities.