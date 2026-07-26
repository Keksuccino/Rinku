/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import org.junit.jupiter.api.AfterEach;

import java.io.IOException;

/** Releases process-lifetime installer leases before JUnit removes temporary directories. */
abstract class MCEFInstallerTestBase {
    @AfterEach
    final void releaseInstallerLeases() throws IOException {
        MCEFGenerationLeaseRegistry.releaseAllForTests();
    }
}
