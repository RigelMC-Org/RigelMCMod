package org.rigelmc.protect.worldedit;

/**
 * The pure decision behind {@link FragileBlockPolicy}: given a block's registry flags, is it
 * one that cannot support itself and therefore pops off into a dropped item the moment
 * whatever it is attached to changes?
 *
 * <p><b>Deliberately its own class, with no WorldEdit imports at all.</b> This is not
 * gratuitous splitting - {@code worldedit-core} is a {@code compileOnly} dependency, so it
 * is absent from the test runtime classpath, and a test cannot even load a class whose
 * method signatures mention {@code BlockStateHolder} (verified: doing exactly that failed
 * with "cannot access BlockStateHolder"). Keeping the decision in a WorldEdit-free class is
 * what makes it testable at all, and it is the same "keep the decision pure, keep the
 * wrapper thin" convention as {@code protect.antigrief.CommandBlockGuard}.</p>
 */
final class FragileBlockRule {

    private FragileBlockRule() {
    }

    /**
     * @param fragileWhenPushed WorldEdit's {@code BlockMaterial#isFragileWhenPushed()} -
     *     Minecraft's {@code PushReaction.DESTROY}, i.e. "breaks into an item rather than
     *     moving". Covers torches, saplings, flowers, redstone, rails, buttons, plates.
     * @param solid whether the block is solid. The non-solid fallback is belt-and-braces
     *     rather than redundancy: a few support-requiring decorations are pushed by pistons
     *     rather than destroyed, so the flag above alone would miss them.
     * @param air air is non-solid but obviously not a decoration - and without this
     *     carve-out {@code //set air}, the single most common WorldEdit operation there is,
     *     would count against the cap and halt long before any real fragile fill did.
     * @param liquid same reasoning as {@code air}.
     */
    static boolean isFragile(boolean fragileWhenPushed, boolean solid, boolean air, boolean liquid) {
        if (air || liquid) {
            return false;
        }
        return fragileWhenPushed || !solid;
    }
}
