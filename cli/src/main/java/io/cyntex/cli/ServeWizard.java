package io.cyntex.cli;

import io.cyntex.core.model.ServeResource;

import java.util.List;

/**
 * The interactive standalone {@code serve} flow: ask the id, then collect one or more publish surfaces
 * (sync / query / push) via {@link ServeSurfaceWizard}, and build a canonical {@code kind: serve}
 * definition body — pure structure with no {@code from:} wiring (X19); a pipeline supplies the wiring at
 * the use site. It collects answers through a {@link Prompter}, never a terminal directly.
 */
final class ServeWizard {

    private final Prompter prompter;
    private final List<String> existingSourceIds;

    ServeWizard(Prompter prompter, List<String> existingSourceIds) {
        this.prompter = prompter;
        this.existingSourceIds = existingSourceIds;
    }

    ServeResource run() {
        String id = WizardPrompts.askId(prompter, "Serve id", "serve");
        ServeSurfaceWizard.Surfaces surfaces =
                new ServeSurfaceWizard(prompter, existingSourceIds).collect();
        return new ServeResource(id, null, surfaces.sync(), surfaces.query(), surfaces.push(), null);
    }
}
