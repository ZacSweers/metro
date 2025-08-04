```mermaid
flowchart TD
    aggregator --> common
    aggregator --> contributor
    aggregator --> parent-graph
    app --> aggregator
    app --> child-graph
    app --> common
    app --> contributor
    app --> parent-graph
    child-graph --> common
    child-graph --> parent-graph
    contributor --> common
    parent-graph --> common
```
