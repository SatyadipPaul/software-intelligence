# The questions Jev is asked

Generated from `sample-commerce` by `python explain_questions.py`. Each block is one request: the **state** Jev reads and the **questions** it answers in parallel. Every question passed `lint()` (the question check) before it could be shown here.

## Round 1: the role of one class

OrderRepository has no annotation, so only a reading of its code can say what it is.

### Questions

```json
{
  "role": {
    "type": "choice",
    "instructions": {
      "question": "Which role does the class described in `type` play in this application?",
      "inspect": [
        "type",
        "uses",
        "used_by",
        "entry_points"
      ],
      "focus": "Judge by what the class declares and does, including its code in `type.code`. Its name is a hint, not evidence."
    },
    "criteria": {
      "CONTROLLER": {
        "what": "Receives HTTP requests and hands them to other code.",
        "signs": [
          "@RestController or @Controller",
          "methods mapped to HTTP routes, listed in `entry_points`"
        ],
        "not": "command-line commands or RPC/MCP servers: those are ENTRY_POINT"
      },
      "ENTRY_POINT": {
        "what": "Receives input from outside the application other than HTTP: a command-line command, an RPC or MCP server, a scheduled job or a main method.",
        "signs": [
          "parses arguments or incoming requests",
          "a main method, a CLI framework annotation, or a request loop",
          "hands the work to other classes"
        ]
      },
      "MESSAGE_CONSUMER": {
        "what": "Reacts to messages or events that arrive from a queue, topic or event bus.",
        "signs": [
          "@KafkaListener, @RabbitListener or @EventListener methods",
          "topics listed in `entry_points`"
        ]
      },
      "SERVICE": {
        "what": "Coordinates one business use case of the application, usually called by a controller or consumer.",
        "signs": [
          "@Service",
          "calls repositories, clients or other services in a sequence"
        ],
        "not": "a general algorithm or processing engine: that is ENGINE"
      },
      "ENGINE": {
        "what": "Implements a core algorithm or processing step - parsing, analysing, indexing, ranking, planning, building - over data it is given.",
        "signs": [
          "substantial logic of its own",
          "works with several collaborators or data structures"
        ],
        "not": "a small stateless helper: that is UTILITY"
      },
      "REPOSITORY_COMPONENT": {
        "what": "Saves or loads stored records in a database on behalf of other code.",
        "signs": [
          "@Repository or a Spring Data interface",
          "JPA, JDBC or SQL"
        ],
        "not": "writing files or JSON: that is SERIALIZER"
      },
      "ENTITY": {
        "what": "A business record that is mapped to a database table.",
        "signs": [
          "@Entity or @Table",
          "fields that become columns"
        ],
        "not": "an in-memory data structure: that is DATA_MODEL"
      },
      "DATA_MODEL": {
        "what": "An in-memory data structure or value type: mostly fields, records or enums, little behaviour, not mapped to a database.",
        "signs": [
          "a record, enum or class of fields",
          "passed between other classes"
        ]
      },
      "EXTERNAL_CLIENT": {
        "what": "Calls a system outside this application: another service, a vendor API or a remote store.",
        "signs": [
          "HTTP clients such as RestTemplate, WebClient or Feign",
          "remote URLs or a vendor SDK"
        ],
        "not": "only formatting data for a file or stream: that is SERIALIZER"
      },
      "SERIALIZER": {
        "what": "Converts objects to or from an external format: JSON, XML, CSV, files or a wire protocol.",
        "signs": [
          "reads or writes files or streams",
          "names such as Json, Writer, Reader, Codec, Exporter"
        ]
      },
      "CONFIGURATION": {
        "what": "Holds settings or sets the application up, rather than doing its work.",
        "signs": [
          "@Configuration or @Bean methods",
          "an options or settings holder",
          "property binding"
        ]
      },
      "UTILITY": {
        "what": "A small stateless helper with no collaborators: formatting, parsing a string, conversions.",
        "signs": [
          "mostly static methods",
          "no fields holding other classes"
        ]
      }
    }
  },
  "fits_a_role": {
    "type": "noul",
    "instructions": {
      "question": "Does the class in `type` plainly play one of these roles: controller, entry point, message consumer, service, engine, repository component, entity, data model, external client, serializer, configuration or utility?",
      "inspect": [
        "type",
        "uses",
        "used_by"
      ]
    },
    "criteria": {
      "true": "One of the listed roles describes the class's main job.",
      "false": "None of them does: for example an exception type, test support or generated code."
    }
  },
  "name_misleads": {
    "type": "noul",
    "instructions": {
      "question": "Does the name in `type.name` promise a role or behaviour that the class does not deliver?",
      "inspect": [
        "type"
      ],
      "focus": "Compare what the name claims - Repository, Gateway, Controller, Service, Consumer, Command - with what the fields and methods actually do."
    },
    "criteria": {
      "true": "The name claims something the code does not do: for example a Repository that stores nothing, or a Gateway that calls nothing outside the application.",
      "false": "The class does what its name says, or its name makes no such claim."
    }
  }
}
```

### State

```json
{
  "context": {
    "repository": "sample-commerce",
    "language": "Java",
    "frameworks": [
      "Spring stereotypes: @Service, @Component, @Repository (imported by 3 of 10 files)",
      "Spring Web: HTTP controllers and routes (imported by 2 of 10 files)",
      "Spring Kafka: message listeners and producers (imported by 1 of 10 files)",
      "Spring Security: access rules (imported by 1 of 10 files)",
      "Spring transactions (imported by 1 of 10 files)",
      "Spring beans and injected configuration values (imported by 1 of 10 files)"
    ],
    "graph_being_built": "A knowledge graph of this codebase for answering questions about it: nodes are classes, edges are relationships between classes, and groups of classes are business capabilities."
  },
  "type": {
    "name": "OrderRepository",
    "qualified_name": "com.acme.checkout.OrderRepository",
    "declaration": "class",
    "package": "com.acme.checkout",
    "annotations": [],
    "extends": [],
    "implements": [],
    "fields": [],
    "methods": [
      {
        "name": "save",
        "parameters": [
          "String customerId",
          "BigDecimal total"
        ],
        "returns": "String",
        "annotations": []
      }
    ],
    "code": "public final class OrderRepository {\n    public String save(String customerId, BigDecimal total) { return \"order-1\"; }\n}"
  },
  "uses": [],
  "used_by": [
    {
      "class": "CheckoutService",
      "how": [
        "field `orders` has type OrderRepository",
        "field `orders` is initialised with new OrderRepository()",
        "method `execute` calls `orders.save(...)`"
      ]
    }
  ],
  "entry_points": []
}
```

## Round 2: what one proven link means

BillingLedger names PaymentGateway, but only calls `getClass()` on it: syntax proves the link, only judgment can say it is incidental.

### Questions

```json
{
  "essential": {
    "type": "noul",
    "instructions": {
      "question": "Does `from_type` delegate part of its own job to `to_type`?",
      "inspect": [
        "from_type",
        "to_type",
        "evidence"
      ],
      "focus": "What `from_type` exists to do, and whether it needs `to_type`'s operations to do it."
    },
    "criteria": {
      "true": "`from_type` calls operations of `to_type` to carry out its own responsibility; removing `to_type` would break that job.",
      "false": "`to_type` is incidental: only passed through, stored without being used, logged, or used for its class name or other methods every object has."
    }
  },
  "persists": {
    "type": "noul",
    "instructions": {
      "question": "Does `from_type` save `to_type` to a database, or load it from one?",
      "inspect": [
        "from_type",
        "to_type",
        "evidence"
      ]
    },
    "criteria": {
      "true": "`from_type` writes `to_type` records to, or reads them from, a database - tables, documents or a key-value store - for example through JPA, JDBC, SQL or a Spring Data repository.",
      "false": "No database is involved. Writing `to_type` to a file, JSON, a stream or the console does not count; that is serialising."
    }
  },
  "serializes": {
    "type": "noul",
    "instructions": {
      "question": "Does `from_type` convert `to_type` to or from an external format?",
      "inspect": [
        "from_type",
        "to_type",
        "evidence"
      ]
    },
    "criteria": {
      "true": "`from_type` turns `to_type` into, or builds it from, JSON, XML, CSV, a file or a wire message.",
      "false": "`from_type` never converts `to_type` to or from any external format."
    }
  },
  "publishes": {
    "type": "noul",
    "instructions": {
      "question": "Does `from_type` send `to_type` as a message or event for other parts of the system?",
      "inspect": [
        "from_type",
        "to_type",
        "evidence"
      ]
    },
    "criteria": {
      "true": "`from_type` hands `to_type`, or data describing it, to a message broker, topic, queue or event bus.",
      "false": "`from_type` sends no message or event carrying `to_type`."
    }
  }
}
```

### State

```json
{
  "context": {
    "repository": "sample-commerce",
    "language": "Java",
    "frameworks": [
      "Spring stereotypes: @Service, @Component, @Repository (imported by 3 of 10 files)",
      "Spring Web: HTTP controllers and routes (imported by 2 of 10 files)",
      "Spring Kafka: message listeners and producers (imported by 1 of 10 files)",
      "Spring Security: access rules (imported by 1 of 10 files)",
      "Spring transactions (imported by 1 of 10 files)",
      "Spring beans and injected configuration values (imported by 1 of 10 files)"
    ],
    "graph_being_built": "A knowledge graph of this codebase for answering questions about it: nodes are classes, edges are relationships between classes, and groups of classes are business capabilities."
  },
  "from_type": {
    "name": "BillingLedger",
    "qualified_name": "com.acme.billing.BillingLedger",
    "declaration": "class",
    "package": "com.acme.billing",
    "annotations": [
      "@Service"
    ],
    "extends": [],
    "implements": [],
    "fields": [
      {
        "name": "audit",
        "type": "AuditTrail",
        "annotations": []
      },
      {
        "name": "gateway",
        "type": "PaymentGateway",
        "annotations": []
      }
    ],
    "methods": [
      {
        "name": "summary",
        "parameters": [
          "String endpoint"
        ],
        "returns": "String",
        "annotations": []
      }
    ],
    "code": "@Service\npublic class BillingLedger {\n    private final AuditTrail audit = new AuditTrail();\n    private final PaymentGateway gateway = new PaymentGateway();\n\n    public String summary(String endpoint) {\n        return audit.record(gateway.getClass().getSimpleName()) + \" via \" + endpoint;\n    }\n}"
  },
  "to_type": {
    "name": "PaymentGateway",
    "qualified_name": "com.acme.checkout.PaymentGateway",
    "declaration": "class",
    "package": "com.acme.checkout",
    "annotations": [],
    "extends": [],
    "implements": [],
    "fields": [],
    "methods": [
      {
        "name": "authorize",
        "parameters": [
          "String customerId",
          "BigDecimal total"
        ],
        "returns": "void",
        "annotations": []
      }
    ],
    "code": "public final class PaymentGateway {\n    public void authorize(String customerId, BigDecimal total) { }\n}"
  },
  "proven_by_syntax": [
    "CREATES",
    "DEPENDS_ON"
  ],
  "evidence": [
    {
      "how": "field `gateway` has type PaymentGateway",
      "line": "14",
      "code": "private final PaymentGateway gateway = new PaymentGateway();"
    },
    {
      "how": "field `gateway` is initialised with new PaymentGateway()",
      "line": "14",
      "code": "private final PaymentGateway gateway = new PaymentGateway();"
    },
    {
      "how": "method `summary` calls `gateway.getClass(...)`",
      "line": "17",
      "code": "return audit.record(gateway.getClass().getSimpleName()) + \" via \" + endpoint;"
    }
  ]
}
```

## Round 3: what one community is

The group Louvain found around the payment route; `inferred_role` values are earlier answers, marked as such.

### Questions

```json
{
  "label": {
    "type": "choice",
    "instructions": {
      "question": "Which name best describes what the group of classes in `members` does?",
      "inspect": [
        "members",
        "relations",
        "other_groups"
      ],
      "focus": "Each option says where in the code the name comes from. `other_groups` lists what the other groups are about; prefer a name that tells this group apart from them."
    },
    "criteria": {
      "payments": "Named by class names PaymentController, PaymentService; route /payments/authorize",
      "checkout": "Named by package com.acme.checkout"
    }
  },
  "single_theme": {
    "type": "noul",
    "instructions": {
      "question": "Do the classes in `members` work toward one purpose that a single short name could describe?",
      "inspect": [
        "members",
        "relations"
      ]
    },
    "criteria": {
      "true": "The members serve one purpose, for example all take part in handling payments.",
      "false": "The members serve unrelated purposes and were grouped only because some reference each other."
    }
  },
  "cohesion": {
    "type": "score",
    "instructions": {
      "question": "How tightly do the classes in `members` belong together as one unit?",
      "inspect": [
        "members",
        "relations"
      ]
    },
    "criteria": [
      "No entries in `relations` connect the members, and they share no entry point.",
      "The members share a package or vocabulary, but `relations` shows few links and none of them essential.",
      "The members form one call path in `relations`, but at least one member serves a different purpose.",
      "Every member takes part in the same flow or handles the same data, linked by essential relations."
    ]
  },
  "business_capability": {
    "type": "noul",
    "instructions": {
      "question": "Does the group in `members` provide a feature that a user of this application would name, given what the application is (see `context`)?",
      "inspect": [
        "members",
        "context"
      ]
    },
    "criteria": {
      "true": "Users would name it as something the application does for them: for a shop, taking payments; for a developer tool, impact analysis or answering questions about code.",
      "false": "It is internal plumbing that users never see: logging, configuration, shared helpers or data holders."
    }
  }
}
```

### State

```json
{
  "context": {
    "repository": "sample-commerce",
    "language": "Java",
    "frameworks": [
      "Spring stereotypes: @Service, @Component, @Repository (imported by 3 of 10 files)",
      "Spring Web: HTTP controllers and routes (imported by 2 of 10 files)",
      "Spring Kafka: message listeners and producers (imported by 1 of 10 files)",
      "Spring Security: access rules (imported by 1 of 10 files)",
      "Spring transactions (imported by 1 of 10 files)",
      "Spring beans and injected configuration values (imported by 1 of 10 files)"
    ],
    "graph_being_built": "A knowledge graph of this codebase for answering questions about it: nodes are classes, edges are relationships between classes, and groups of classes are business capabilities."
  },
  "members": [
    {
      "class": "PaymentController",
      "package": "com.acme.checkout",
      "inferred_role": {
        "value": "SERVICE",
        "confidence": 0.9,
        "judged_by": "example"
      },
      "methods": [
        "authorize"
      ],
      "entry_points": [
        "POST /payments/authorize"
      ]
    },
    {
      "class": "PaymentService",
      "package": "com.acme.checkout",
      "inferred_role": {
        "value": "SERVICE",
        "confidence": 0.9,
        "judged_by": "example"
      },
      "methods": [
        "authorize"
      ],
      "entry_points": []
    }
  ],
  "relations": [],
  "links_outside": [],
  "other_groups": []
}
```

