/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.retaildemo;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.retaildemo.DataReaderWriter;

import java.io.File;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import static android.support.test.InstrumentationRegistry.getTargetContext;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DataReaderWriterTest {

    @Test
    public void testSetGetElapsedTime() {
        final long elapsedRealTime = 20000;
        DataReaderWriter.setElapsedRealTime(getTargetContext(), elapsedRealTime);
        assertEquals(elapsedRealTime, DataReaderWriter.getElapsedRealTime(getTargetContext()));
    }

    @Test
    public void testSetGetElapsedTime_fileAlreadyExists() throws Exception {
        new File(DataReaderWriter.getFilePath(getTargetContext())).createNewFile();

        final long elapsedRealTime = 40000;
        DataReaderWriter.setElapsedRealTime(getTargetContext(), elapsedRealTime);
        assertEquals(elapsedRealTime, DataReaderWriter.getElapsedRealTime(getTargetContext()));
    }

    @After
    public void tearDown() {
        final File file = new File(DataReaderWriter.getFilePath(getTargetContext()));
        if (file.exists()) {
            file.delete();
        }
    }
}