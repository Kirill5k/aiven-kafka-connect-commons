/*
 * Copyright 2021 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.common.output.parquet;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.sink.SinkRecord;

import io.aiven.kafka.connect.common.config.OutputField;
import io.aiven.kafka.connect.common.config.OutputFieldEncodingType;
import io.aiven.kafka.connect.common.config.OutputFieldType;

import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParquetOutputWriterTest {

    @Test
    void testWriteAllFields(@TempDir final Path tmpDir) throws IOException {
        final var parquetFile = tmpDir.resolve("parquet.file");
        final var values = List.of("a", "b", "c", "d");
        writeRecords(
                parquetFile,
                List.of(
                        new OutputField(OutputFieldType.KEY, OutputFieldEncodingType.NONE),
                        new OutputField(OutputFieldType.OFFSET, OutputFieldEncodingType.NONE),
                        new OutputField(OutputFieldType.TIMESTAMP, OutputFieldEncodingType.NONE),
                        new OutputField(OutputFieldType.HEADERS, OutputFieldEncodingType.NONE),
                        new OutputField(OutputFieldType.VALUE, OutputFieldEncodingType.NONE)
                ),
                SchemaBuilder.STRING_SCHEMA,
                values,
                true
        );
        var counter = 0;
        final var timestamp = 1000;
        for (final var r : readRecords(parquetFile)) {
            final var expectedString =
                    "{\"key\": \"some-key-" + counter + "\", "
                            + "\"offset\": 100, "
                            + "\"timestamp\": "
                            + (timestamp + counter) + ", "
                            + "\"headers\": "
                            + "{\"a\": {\"bytes\": \"b\"}, \"c\": {\"bytes\": \"d\"}}, "
                            + "\"value\": \"" + values.get(counter) + "\"}";
            assertEquals(expectedString, r);
            counter++;
        }
    }

    @Test
    void testWritePartialFields(@TempDir final Path tmpDir) throws IOException {
        final var parquetFile = tmpDir.resolve("parquet.file");
        final var values = List.of("a", "b", "c", "d");
        writeRecords(
                parquetFile,
                List.of(
                        new OutputField(OutputFieldType.KEY, OutputFieldEncodingType.NONE),
                        new OutputField(OutputFieldType.VALUE, OutputFieldEncodingType.NONE)
                ),
                SchemaBuilder.STRING_SCHEMA,
                values,
                false
        );
        var counter = 0;
        for (final var r : readRecords(parquetFile)) {
            final var expectedString =
                    "{\"key\": \"some-key-" + counter + "\", "
                            + "\"value\": \"" + values.get(counter) + "\"}";
            assertEquals(expectedString, r);
            counter++;
        }
    }

    @Test
    void testWriteValueStruct(@TempDir final Path tmpDir) throws IOException {
        final var parquetFile = tmpDir.resolve("parquet.file");
        final var recordSchema =
                SchemaBuilder.struct()
                    .field("name", Schema.STRING_SCHEMA)
                    .field("age", Schema.INT32_SCHEMA)
                .build();

        final var values =
                List.of(
                        new Struct(recordSchema)
                                .put("name", "name-0").put("age", 0),
                        new Struct(recordSchema)
                                .put("name", "name-1").put("age", 1),
                        new Struct(recordSchema)
                                .put("name", "name-2").put("age", 2),
                        new Struct(recordSchema)
                                .put("name", "name-3").put("age", 3)
                );
        writeRecords(
                parquetFile,
                List.of(new OutputField(OutputFieldType.VALUE, OutputFieldEncodingType.NONE)),
                recordSchema,
                values,
                false
        );
        var counter = 0;
        for (final var r : readRecords(parquetFile)) {
            final var expectedString = "{\"value\": {\"name\": \"name-" + counter + "\", \"age\": " + counter + "}}";
            assertEquals(expectedString, r);
            counter++;
        }
    }

    @Test
    void testWriteValueArray(@TempDir final Path tmpDir) throws IOException {
        final var parquetFile = tmpDir.resolve("parquet.file");
        final var recordSchema = SchemaBuilder.array(Schema.INT32_SCHEMA).build();

        final var values =
                List.of(
                        List.of(1, 2, 3, 4),
                        List.of(5, 6, 7, 8),
                        List.of(9, 10)
                );
        writeRecords(
                parquetFile,
                List.of(new OutputField(OutputFieldType.VALUE, OutputFieldEncodingType.NONE)),
                recordSchema,
                values,
                false
        );
        var counter = 0;
        for (final var r : readRecords(parquetFile)) {
            final var expectedString = "{\"value\": " + values.get(counter) + "}";
            assertEquals(expectedString, r);
            counter++;
        }
    }

    @Test
    void testWriteValueMap(@TempDir final Path tmpDir) throws IOException {
        final var parquetFile = tmpDir.resolve("parquet.file");
        final var recordSchema = SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build();

        writeRecords(
                parquetFile,
                List.of(new OutputField(OutputFieldType.VALUE, OutputFieldEncodingType.NONE)),
                recordSchema,
                List.of(Map.of("a", 1, "b", 2)),
                false
        );
        for (final var r : readRecords(parquetFile)) {
            final var mapValue =  "{\"a\": 1, \"b\": 2}";
            final var expectedString = "{\"value\": " + mapValue + "}";
            assertEquals(expectedString, r);
        }
    }

    private <T> void writeRecords(final Path parquetFile,
                                  final Collection<OutputField> fields,
                                  final Schema recordSchema,
                                  final List<T> records,
                                  final boolean withHeaders) throws IOException {
        final OutputStream out = new FileOutputStream(parquetFile.toFile());
        final Headers headers = new ConnectHeaders();
        headers.add("a", "b".getBytes(StandardCharsets.UTF_8), Schema.BYTES_SCHEMA);
        headers.add("c", "d".getBytes(StandardCharsets.UTF_8), Schema.BYTES_SCHEMA);
        try (final var o = out;
             final var parquetWriter = new ParquetOutputWriter(fields, o, Collections.emptyMap())) {
            int counter = 0;
            final var sinkRecords = new ArrayList<SinkRecord>();
            for (final var r : records) {
                final var sinkRecord =
                        new SinkRecord(
                                "some-topic", 1,
                                Schema.STRING_SCHEMA, "some-key-" + counter,
                                recordSchema, r,
                                100L, 1000L + counter,
                                TimestampType.CREATE_TIME,
                                withHeaders ? headers : null);
                sinkRecords.add(sinkRecord);
                counter++;
            }
            parquetWriter.writeRecords(sinkRecords);
        }

    }

    private List<String> readRecords(final Path parquetFile) throws IOException {
        final var inputFile = new ParquetInputFile(parquetFile);
        final var records = new ArrayList<String>();
        try (final var reader =
                     AvroParquetReader.builder(inputFile)
                             .withCompatibility(false)
                             .build()) {
            var r = reader.read();
            while (r != null) {
                records.add(r.toString());
                r = reader.read();
            }
        }
        return records;
    }

    static class ParquetInputFile implements InputFile {

        final SeekableByteChannel seekableByteChannel;

        ParquetInputFile(final Path tmpFilePath) throws IOException {
            this.seekableByteChannel = Files.newByteChannel(tmpFilePath, StandardOpenOption.READ);
        }

        @Override
        public long getLength() throws IOException {
            return seekableByteChannel.size();
        }

        @Override
        public SeekableInputStream newStream() throws IOException {
            return new DelegatingSeekableInputStream(Channels.newInputStream(seekableByteChannel)) {
                @Override
                public long getPos() throws IOException {
                    return seekableByteChannel.position();
                }

                @Override
                public void seek(final long newPosition) throws IOException {
                    seekableByteChannel.position(newPosition);
                }
            };
        }
    }

}
