.PHONY: fmt fmt-check lint test

fmt:
	clojure -M:format/fix

fmt-check:
	clojure -M:format

lint:
	clojure -M:lint/clj-kondo
	clojure -M:lint/splint

test:
	clojure -M:test
