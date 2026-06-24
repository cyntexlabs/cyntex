package io.cyntex.cli;

import io.cyntex.core.model.ViewResource;

/**
 * The interactive standalone {@code view} flow: ask the id and a primary key, then build a canonical
 * {@code kind: view} definition body. A standalone view is a reusable MDM sink — pure structure with no
 * {@code from:} wiring (X19); a pipeline supplies the wiring at the use site. Richer fields (storage,
 * schema) are authored by hand. It collects answers through a {@link Prompter}, never a terminal
 * directly.
 */
final class ViewWizard {

    private final Prompter prompter;

    ViewWizard(Prompter prompter) {
        this.prompter = prompter;
    }

    ViewResource run() {
        String id = WizardPrompts.askId(prompter, "View id", "view");
        String primaryKey = WizardPrompts.askPrimaryKey(prompter);
        return new ViewResource(id, null, primaryKey, null, null, null);
    }
}
