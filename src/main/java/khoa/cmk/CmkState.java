package khoa.cmk;

/**
 * Small holder for toggles that mixins need to read. Kept separate from
 * CmkCommands so the mixin package doesn't need to depend on command
 * registration classes.
 */
public final class CmkState {
	private CmkState() {}

	/** /allbedrockbreakable - when true, any block with -1 destroySpeed
	 * (bedrock, barrier, etc.) mines at obsidian hardness instead. */
	public static volatile boolean allBedrockBreakable = false;
}
