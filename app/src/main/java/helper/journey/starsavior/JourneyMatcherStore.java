package helper.journey.starsavior;

/** Atomically swaps matcher data only after a complete database load succeeds. */
final class JourneyMatcherStore {
    interface Loader {
        JourneyModels.Data load() throws Exception;
    }

    private volatile State state;
    private GameLanguage expectedLanguage;

    JourneyMatcher current() {
        State current = state;
        return current == null ? null : current.matcher;
    }

    JourneyModels.Data currentData() {
        State current = state;
        return current == null ? null : current.data;
    }

    synchronized void selectLanguage(GameLanguage language) {
        expectedLanguage = language;
        state = null;
    }

    JourneyModels.Data reload(Loader loader) throws Exception {
        JourneyModels.Data data = loader.load();
        JourneyMatcher replacement = new JourneyMatcher(data.events, GameLanguage.require(data.language));
        synchronized (this) {
            if (expectedLanguage == null || expectedLanguage.tag.equals(data.language)) {
                state = new State(data, replacement);
            }
        }
        return data;
    }

    private static final class State {
        final JourneyModels.Data data;
        final JourneyMatcher matcher;

        State(JourneyModels.Data data, JourneyMatcher matcher) {
            this.data = data;
            this.matcher = matcher;
        }
    }
}
