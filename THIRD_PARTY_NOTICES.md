# Third-Party Notices

The project source is licensed under Apache License 2.0 as stated in the root `LICENSE` file. This project also depends on third-party libraries under their own licenses; the project license does not replace or narrow those terms.

## Direct frontend dependencies

| Package | Version | License |
| --- | --- | --- |
| `@ant-design/icons-vue` | 7.0.1 | MIT |
| `@vueuse/core` | 14.3.0 | MIT |
| `ant-design-vue` | 4.2.6 | MIT |
| `echarts` | 6.1.0 | Apache-2.0 |
| `pinia` | 3.0.4 | MIT |
| `vue` | 3.5.39 | MIT |
| `vue-echarts` | 8.0.1 | MIT |
| `vue-router` | 5.1.0 | MIT |

The complete npm graph is fixed by `frontend/package-lock.json`. The Stage 5.5 audit resolved license metadata for 480 package entries with no missing or unexplained license field.

## Direct backend dependencies

| Dependency family | Version | License recorded by the local Maven POM |
| --- | --- | --- |
| Spring Boot starters | 3.5.16 | Apache License 2.0 |
| Spring Security Crypto | 6.5.11 | Apache License 2.0 |
| Spring AI | 1.1.8 | Apache License 2.0 |
| MyBatis-Plus | 3.5.9 | Apache License 2.0 |
| Sa-Token | 1.39.0 | Apache License 2.0 |
| MySQL Connector/J | 9.7.0 | GPLv2 with Universal FOSS Exception 1.0 |
| H2 Database | 2.3.232 | MPL 2.0 or EPL 1.0 |

The complete Maven runtime graph is resolved from `backend/pom.xml`. The Stage 5.5 audit checked 120 runtime dependencies, including inherited POM license declarations, with no missing or unexplained entry.

## Distribution boundary

The public GitHub Release contains source, documentation, release notes, and hashes only. It does not attach `node_modules`, dependency binaries, the locally built Spring Boot JAR, frontend `dist`, test reports, or database artifacts. Anyone redistributing a built application must preserve the applicable third-party notices and license obligations.
