# Scenes still to be ported

These are the old StageWright scenes worth keeping, and the helpers they use, moved here unchanged
after StageWright deleted the scene API they were written against. Nothing here is compiled. The
folders mirror the package paths of the testmod. When a scene is rewritten as a test on the new path
(`net.magicterra.stagewright.test`), or as a plain unit test, delete it from here in the same commit;
a helper goes once nothing here uses it. The folder is gone when the port is done.

What each old helper became on the new path is in the javadoc of the shared kit,
`common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/kit/package-info.java`.
