# oscnews

A Clojure library designed to fetch osc news as html and convert to epub if possible.

Note: convertion to epub requires linux os and Calibre/bin in PATH.

## Usage

Run from source, use ```lein run [page-number]```

```
# Download page 1, page number starts from 1
lein run 1
# Or create runnable jar and execute
lein uberjar
java -jar ./target/oscnews-0.1.0-SNAPSHOT-standalone.jar [page-number]
```

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
