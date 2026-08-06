.PHONY: fmt fmt-check lint test identity-check

fmt:
	clojure -M:format/fix

fmt-check:
	clojure -M:format

lint:
	clojure -M:lint/clj-kondo
	clojure -M:lint/splint

test:
	clojure -M:test

identity-check:
	bash bin/identity-check
