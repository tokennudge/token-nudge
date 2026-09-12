package io.github.tokennudge.camunda7;

/**
 * The engine Docker images/commands used by the integration tests, selected via the
 * {@code tokennudge.it.engine} system property ({@code camunda}, the default, or
 * {@code cibseven}). Both tags and their start commands were verified against real
 * containers.
 */
final class EngineImages {

    static final String CAMUNDA_IMAGE = "camunda/camunda-bpm-platform:run-7.24.0";
    static final String[] CAMUNDA_COMMAND = {"./camunda.sh", "--rest"};

    static final String CIBSEVEN_IMAGE = "cibseven/cibseven:run-2.2.0";
    static final String[] CIBSEVEN_COMMAND = {"./cibseven.sh", "--rest"};

    private EngineImages() {
    }

    static boolean isCibSeven() {
        return "cibseven".equals(System.getProperty("tokennudge.it.engine", "camunda"));
    }

    static String image() {
        return isCibSeven() ? CIBSEVEN_IMAGE : CAMUNDA_IMAGE;
    }

    static String[] command() {
        return isCibSeven() ? CIBSEVEN_COMMAND : CAMUNDA_COMMAND;
    }
}
