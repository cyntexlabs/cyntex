# Connector catalog ingest report

Connector repo SHA: `b4151f21`
Ingested connectors: 77

## Unclassified — no resolvable mode (need cyntex.modes)
- ai-chat
- bes-channels
- bigquery
- databend
- elasticsearch
- hudi
- lark-im
- lark-task
- tablestore
- vika

## Not derived — no built jar or did not classload (excluded from refresh)
- hazelcast
- kafka_avro
- postgres

## MQ suspects — derived cdc, undeclared (need cyntex.modes)
(none)

## Sink semantics defaulted — no DML signal
- activemq
- bigquery
- csv
- custom
- doris
- dummy
- file-stream
- kafka
- kafka_enhanced
- rabbitmq
- redis
- rocketmq
- tablestore
- vika

## Unrecognized type tokens — fell to string input
(none)

## Unresolved label refs — fell back to raw key
- aliyun-adb-mysql:addtionalString
- aliyun-rds-mysql:addtionalString
- aws-rds-mysql:addtionalString
- mysql-pxc:addtionalString
- polar-db-mysql:addtionalString
- tencent-db-mariadb:addtionalString

## Exemptions — modules and specs set aside
- [EXCLUDED] coding-demo-connector: known non-connector module
- [EXCLUDED] demo-connector: known non-connector module
- [EXCLUDED] js-core: known non-connector module
- [EXCLUDED] mock-source-connector: known non-connector module
- [EXCLUDED] mock-target-connector: known non-connector module
- [EXCLUDED] tdd-connector: known non-connector module
- [MULTI_SPEC] bigquery-connector: spec.json
- [MULTI_SPEC] coding-connector: spec.json
- [MULTI_SPEC] lark-doc-connector: spec-oauth.json
- [NO_CANONICAL_SPEC] connector-perf-test: no @TapConnectorClass and no spec.json
