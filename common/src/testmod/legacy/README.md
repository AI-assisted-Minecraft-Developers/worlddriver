# Scenes still to be ported

These are the old StageWright scenes worth keeping, and the helpers they use, moved here unchanged
after StageWright deleted the scene API they were written against. Nothing here is compiled. The
folders mirror the package paths: `java/` holds the scenes and their helpers from the testmod, and
`test/` holds a `:common` unit test that checked those scenes. When a scene is rewritten as a test
on the new path (`net.magicterra.stagewright.test`), or as a plain unit test, delete it from here in
the same commit; a helper goes once nothing here uses it. The folder is gone when the port is done.
