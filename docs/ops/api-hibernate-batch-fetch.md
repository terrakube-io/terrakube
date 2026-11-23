# API Hibernate batch fetching

When a JSON:API request includes related resources, Elide reads each included row's relationships. A lazy collection or reference that is not loaded yet costs one SQL statement per row. For example, `GET /api/v1/organization/{org}/workspace/{id}?include=job` reads the step, address and policy evaluation linkage of every job, which is three statements per job without batching.

With batch fetching, Hibernate loads up to `N` pending collections or references of the same kind in one statement, so these requests stay at a fixed number of statements as the number of rows grows.

## Setting

| Property | Default | Environment variable |
| --- | --- | --- |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | `100` | `HibernateBatchFetchSize` |

Set `HibernateBatchFetchSize` on the API container, or pass the property as a JVM system property, for example `-Dspring.jpa.properties.hibernate.default_batch_fetch_size=50` in `JAVA_TOOL_OPTIONS`.

Spring Boot's usual environment variable form, `SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_BATCH_FETCH_SIZE`, does not work for this setting. Spring turns the underscores into dots, so Hibernate receives `hibernate.default.batch.fetch.size`, which it ignores.

## Choosing a value

* `1` turns batching off, which is Hibernate's own default.
* Larger values mean fewer statements but bigger ones. On databases without array parameters, each key in a batch is a bind parameter, and SQL Server allows at most 2,100 parameters per statement.
* Collections annotated with `@BatchSize` (project access, provider version implementations) use their own size instead.
