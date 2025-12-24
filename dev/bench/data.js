window.BENCHMARK_DATA = {
  "lastUpdate": 1766614670886,
  "repoUrl": "https://github.com/ZacSweers/metro",
  "entries": {
    "Startup (baseline)": [
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "700339fda1f58ef172f314b639e42c0a3149e4ce",
          "message": "Add double-graph-like annotation diagnostic (#1575)",
          "timestamp": "2025-12-20T04:52:34Z",
          "tree_id": "7696bdbe571d483a8ed42a6235ec5824f7505503",
          "url": "https://github.com/ZacSweers/metro/commit/700339fda1f58ef172f314b639e42c0a3149e4ce"
        },
        "date": 1766209171826,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.2208381201865796,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "dd3cdf9fc73d5bea12d38406ff7f3c3cb1a2599d",
          "message": "Simplify scenarios, add noop/vanilla, cleanups (#1576)",
          "timestamp": "2025-12-20T05:56:08Z",
          "tree_id": "fe0ec9b5427f8853a16d6f6cacaf36544af4f01b",
          "url": "https://github.com/ZacSweers/metro/commit/dd3cdf9fc73d5bea12d38406ff7f3c3cb1a2599d"
        },
        "date": 1766212943273,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.21714735310131297,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "6785522+JoelWilcox@users.noreply.github.com",
            "name": "Joel Wilcox",
            "username": "JoelWilcox"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "f7facf8a2a2cc119333ebefb548728736c5a9472",
          "message": "Fix rank-based binding replacements getting dropped for multi-contribution classes in root graphs (#1583)",
          "timestamp": "2025-12-22T20:40:51-05:00",
          "tree_id": "d71d835af62a9fbc6fe7efa301b99a18c6bda77e",
          "url": "https://github.com/ZacSweers/metro/commit/f7facf8a2a2cc119333ebefb548728736c5a9472"
        },
        "date": 1766457705331,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.2123365498716701,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "535463d0bf8fae52826f81c8c34b5bbb3b43b2f8",
          "message": "More benchmark work (#1581)",
          "timestamp": "2025-12-22T23:59:38-05:00",
          "tree_id": "9b9a9e58afad23d3be528a522e6743ed3d7d3af1",
          "url": "https://github.com/ZacSweers/metro/commit/535463d0bf8fae52826f81c8c34b5bbb3b43b2f8"
        },
        "date": 1766468792662,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.21225396033386904,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "8711758ca2b90225adc67a9e4a69f7233406bdb6",
          "message": "Introduce `@ComptimeOnly` + stub binds in origin (#1582)",
          "timestamp": "2025-12-23T00:49:13-05:00",
          "tree_id": "58d3288d0a85a46fca25500d2943e213c5498f63",
          "url": "https://github.com/ZacSweers/metro/commit/8711758ca2b90225adc67a9e4a69f7233406bdb6"
        },
        "date": 1766471798500,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.22283317480146284,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "c2dccf402b371ef049b0f82423b24b1dd0c3d12c",
          "message": "Move accesors to be contributed (#1585)",
          "timestamp": "2025-12-23T06:06:25Z",
          "tree_id": "d3d71babb2bee60100e3117d7ce094ee136db2f6",
          "url": "https://github.com/ZacSweers/metro/commit/c2dccf402b371ef049b0f82423b24b1dd0c3d12c"
        },
        "date": 1766472894230,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.2158608035602235,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "japplin@squareup.com",
            "name": "japplin",
            "username": "japplin"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "38572829243ad79bff20c8a29edb4c5540b3c854",
          "message": "Fix bug preventing scope argument from being used in position other than first (#1589)\n\nCo-authored-by: Zac Sweers <pandanomic@gmail.com>",
          "timestamp": "2025-12-23T22:08:07Z",
          "tree_id": "9b3da57189627739c937640a030252006e71de0d",
          "url": "https://github.com/ZacSweers/metro/commit/38572829243ad79bff20c8a29edb4c5540b3c854"
        },
        "date": 1766529513988,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.22133318762355456,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "6785522+JoelWilcox@users.noreply.github.com",
            "name": "Joel Wilcox",
            "username": "JoelWilcox"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "73cda28d857b4d38c98f6b26c8d461c88ebf8be6",
          "message": "Fix rank-based binding replacements getting dropped for multi-contribution classes in graph extensions (#1584)\n\nCo-authored-by: Zac Sweers <pandanomic@gmail.com>",
          "timestamp": "2025-12-24T04:36:39Z",
          "tree_id": "ff6c1d1d9327f622d4d3d72a3284d81c0622083f",
          "url": "https://github.com/ZacSweers/metro/commit/73cda28d857b4d38c98f6b26c8d461c88ebf8be6"
        },
        "date": 1766552801592,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.21788311388645926,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "2979c1d113c64240640a9cb7d42f69ee2442262e",
          "message": "Fix duplicate map key checking (#1591)",
          "timestamp": "2025-12-24T20:55:14Z",
          "tree_id": "70ec9afa558cb55198f4e72d7778214b3446d067",
          "url": "https://github.com/ZacSweers/metro/commit/2979c1d113c64240640a9cb7d42f69ee2442262e"
        },
        "date": 1766611577539,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.21113604793895652,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "89537586f35d508ecae85320bdf5923ece0e77a0",
          "message": "Clarify error (#1593)",
          "timestamp": "2025-12-24T21:48:34Z",
          "tree_id": "02bb2bab13d8a653d7891be83532e5e5d920cb6d",
          "url": "https://github.com/ZacSweers/metro/commit/89537586f35d508ecae85320bdf5923ece0e77a0"
        },
        "date": 1766614668568,
        "tool": "jmh",
        "benches": [
          {
            "name": "baseline",
            "value": 0.22066315344767234,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      }
    ],
    "Startup (current)": [
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "700339fda1f58ef172f314b639e42c0a3149e4ce",
          "message": "Add double-graph-like annotation diagnostic (#1575)",
          "timestamp": "2025-12-20T04:52:34Z",
          "tree_id": "7696bdbe571d483a8ed42a6235ec5824f7505503",
          "url": "https://github.com/ZacSweers/metro/commit/700339fda1f58ef172f314b639e42c0a3149e4ce"
        },
        "date": 1766209175419,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.21224508689795174,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "dd3cdf9fc73d5bea12d38406ff7f3c3cb1a2599d",
          "message": "Simplify scenarios, add noop/vanilla, cleanups (#1576)",
          "timestamp": "2025-12-20T05:56:08Z",
          "tree_id": "fe0ec9b5427f8853a16d6f6cacaf36544af4f01b",
          "url": "https://github.com/ZacSweers/metro/commit/dd3cdf9fc73d5bea12d38406ff7f3c3cb1a2599d"
        },
        "date": 1766212945361,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.22516799708731344,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "6785522+JoelWilcox@users.noreply.github.com",
            "name": "Joel Wilcox",
            "username": "JoelWilcox"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "f7facf8a2a2cc119333ebefb548728736c5a9472",
          "message": "Fix rank-based binding replacements getting dropped for multi-contribution classes in root graphs (#1583)",
          "timestamp": "2025-12-22T20:40:51-05:00",
          "tree_id": "d71d835af62a9fbc6fe7efa301b99a18c6bda77e",
          "url": "https://github.com/ZacSweers/metro/commit/f7facf8a2a2cc119333ebefb548728736c5a9472"
        },
        "date": 1766457707347,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.21884228058965824,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "535463d0bf8fae52826f81c8c34b5bbb3b43b2f8",
          "message": "More benchmark work (#1581)",
          "timestamp": "2025-12-22T23:59:38-05:00",
          "tree_id": "9b9a9e58afad23d3be528a522e6743ed3d7d3af1",
          "url": "https://github.com/ZacSweers/metro/commit/535463d0bf8fae52826f81c8c34b5bbb3b43b2f8"
        },
        "date": 1766468795343,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.21719329423978614,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "8711758ca2b90225adc67a9e4a69f7233406bdb6",
          "message": "Introduce `@ComptimeOnly` + stub binds in origin (#1582)",
          "timestamp": "2025-12-23T00:49:13-05:00",
          "tree_id": "58d3288d0a85a46fca25500d2943e213c5498f63",
          "url": "https://github.com/ZacSweers/metro/commit/8711758ca2b90225adc67a9e4a69f7233406bdb6"
        },
        "date": 1766471800536,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.24110175399997608,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "c2dccf402b371ef049b0f82423b24b1dd0c3d12c",
          "message": "Move accesors to be contributed (#1585)",
          "timestamp": "2025-12-23T06:06:25Z",
          "tree_id": "d3d71babb2bee60100e3117d7ce094ee136db2f6",
          "url": "https://github.com/ZacSweers/metro/commit/c2dccf402b371ef049b0f82423b24b1dd0c3d12c"
        },
        "date": 1766472897324,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.21373754082798674,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "japplin@squareup.com",
            "name": "japplin",
            "username": "japplin"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "38572829243ad79bff20c8a29edb4c5540b3c854",
          "message": "Fix bug preventing scope argument from being used in position other than first (#1589)\n\nCo-authored-by: Zac Sweers <pandanomic@gmail.com>",
          "timestamp": "2025-12-23T22:08:07Z",
          "tree_id": "9b3da57189627739c937640a030252006e71de0d",
          "url": "https://github.com/ZacSweers/metro/commit/38572829243ad79bff20c8a29edb4c5540b3c854"
        },
        "date": 1766529516327,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.22864942783076816,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "6785522+JoelWilcox@users.noreply.github.com",
            "name": "Joel Wilcox",
            "username": "JoelWilcox"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "73cda28d857b4d38c98f6b26c8d461c88ebf8be6",
          "message": "Fix rank-based binding replacements getting dropped for multi-contribution classes in graph extensions (#1584)\n\nCo-authored-by: Zac Sweers <pandanomic@gmail.com>",
          "timestamp": "2025-12-24T04:36:39Z",
          "tree_id": "ff6c1d1d9327f622d4d3d72a3284d81c0622083f",
          "url": "https://github.com/ZacSweers/metro/commit/73cda28d857b4d38c98f6b26c8d461c88ebf8be6"
        },
        "date": 1766552803564,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.21470296098532488,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "2979c1d113c64240640a9cb7d42f69ee2442262e",
          "message": "Fix duplicate map key checking (#1591)",
          "timestamp": "2025-12-24T20:55:14Z",
          "tree_id": "70ec9afa558cb55198f4e72d7778214b3446d067",
          "url": "https://github.com/ZacSweers/metro/commit/2979c1d113c64240640a9cb7d42f69ee2442262e"
        },
        "date": 1766611579698,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.2182053050133132,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "pandanomic@gmail.com",
            "name": "Zac Sweers",
            "username": "ZacSweers"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "89537586f35d508ecae85320bdf5923ece0e77a0",
          "message": "Clarify error (#1593)",
          "timestamp": "2025-12-24T21:48:34Z",
          "tree_id": "02bb2bab13d8a653d7891be83532e5e5d920cb6d",
          "url": "https://github.com/ZacSweers/metro/commit/89537586f35d508ecae85320bdf5923ece0e77a0"
        },
        "date": 1766614670489,
        "tool": "jmh",
        "benches": [
          {
            "name": "current",
            "value": 0.21878684238503684,
            "unit": "ms/op",
            "extra": "iterations: 10\nforks: 2\nthreads: 1"
          }
        ]
      }
    ]
  }
}