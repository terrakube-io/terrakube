# Phase 04: Test Integration

Run comprehensive tests to ensure the rebased code functions correctly and no regressions were introduced. This validates the integration of upstream changes with the fork.

## Tasks

- [ ] Run Maven unit tests using `./mvnw test`
- [ ] Run Maven integration tests using `./mvnw verify`
- [ ] Check test coverage report in coverage/target/site/jacoco-aggregate/
- [ ] Run UI unit tests using `cd ui && npm test`
- [ ] Run UI linting using `cd ui && npm run lint`
- [ ] Start local development environment using docker-compose
- [ ] Verify API service starts successfully and responds to health checks
- [ ] Verify UI service starts successfully and is accessible in browser
- [ ] Test basic workflow: create organization, create workspace, run terraform plan
- [ ] Verify authentication flow works correctly with DEX
- [ ] Check application logs for any errors or warnings
- [ ] Stop docker-compose environment cleanly