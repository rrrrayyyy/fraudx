# FraudX
## Project Overview
This hobby project is fraud detection system with Java, SpringBoot, Kafka, and ScyllaDB

## Requirements
- this project aims to be production-ready, so
    - don't local-optimize (like changing replication factor to lower value)
    - bugs/unexpected behaviors should be identified/fixed
- no data loss (pubsub messages), no data inconsistency
    - but you don't have to restrict settings more than current ones
- executing on local machine
    - so, while aiming for produciton ready system, since it's on the local machine/docker, some resources conflict but this is expected and we need to overcome it

## Components
- payment service
    - publish payment events to Kafka
- fraud detection service
    - subscribe payment events from Kafka
    - (do some fraud detection logic)
    - insert records into ScyllaDB

## Challenges / Purpose
- identify/fix unexpected behaviors/bugs
- **achieve 1 M RPS for both publisher and subscriber**
    - while using local machine resources most efficiently

## fix/build/test/verification
- first, please take a note for your all work in .idea as cumulative context file
    - everytime you work, you also refer to the file and learn what you did earlier
- don't try to test or verify behaviors since there're less tests and executing docker on your sandbox should have different machine resources (meaningless)
