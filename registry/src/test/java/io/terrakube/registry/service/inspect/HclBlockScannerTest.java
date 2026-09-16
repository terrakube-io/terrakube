package io.terrakube.registry.service.inspect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HclBlockScannerTest {

    private static final String MODULE = """
            # A comment with a brace { that must not open anything
            terraform {
              required_providers {
                aws = { source = "hashicorp/aws", version = ">= 5.0" }
              }
            }

            variable "name" {
              type        = string
              description = "Bucket name, may contain \\"quotes\\""
              default     = "acme-${var.env}-logs" // trailing comment }
            }

            variable "tags" {
              type = object({
                team = string
                cost = optional(number, 0)
              })
              description = <<-EOT
                Tags applied to every resource.
                One } brace inside the heredoc.
              EOT
              default = {
                team = "platform"
              }

              validation {
                condition     = length(var.tags.team) > 0
                error_message = "team is required"
              }
            }

            variable "interp" {
              type    = string
              default = "${lookup(var.m, "k")} and %{ if true }x%{ endif }"
            }

            output "arn" {
              description = "The bucket ARN"
              value       = aws_s3_bucket.this.arn
            }

            resource "aws_s3_bucket" "this" {
              bucket = var.name
            }

            resource "aws_s3_bucket_policy" "this" {
              bucket = aws_s3_bucket.this.id
              policy = jsonencode({ Statement = [] })
            }

            data "aws_caller_identity" "current" {}

            locals {
              suffix = "}"
            }
            """;

    @Test
    void findsEveryTopLevelBlockWithItsLabels() {
        List<HclBlockScanner.Block> blocks = HclBlockScanner.topLevelBlocks(MODULE);

        assertThat(blocks).extracting(HclBlockScanner.Block::type)
                .containsExactly("terraform", "variable", "variable", "variable", "output", "resource", "resource", "data", "locals");
        assertThat(blocks.get(5).labels()).containsExactly("aws_s3_bucket", "this");
        assertThat(blocks.get(6).label(1)).isEqualTo("this");
        assertThat(blocks.get(7).labels()).containsExactly("aws_caller_identity", "current");
    }

    @Test
    void readsRawAttributesAndIgnoresNestedBlocks() {
        HclBlockScanner.Block tags = HclBlockScanner.topLevelBlocks(MODULE).get(2);
        Map<String, String> attributes = HclBlockScanner.attributes(tags.body());

        assertThat(attributes).containsOnlyKeys("type", "description", "default");
        assertThat(attributes.get("type")).startsWith("object({").endsWith("})").contains("optional(number, 0)");
        assertThat(attributes.get("default")).isEqualTo("{\n    team = \"platform\"\n  }");
        assertThat(HclBlockScanner.unquote(attributes.get("description")))
                .isEqualTo("Tags applied to every resource.\nOne } brace inside the heredoc.");
    }

    @Test
    void stringsWithTemplatesAndEscapesDoNotBreakNesting() {
        List<HclBlockScanner.Block> blocks = HclBlockScanner.topLevelBlocks(MODULE);
        Map<String, String> name = HclBlockScanner.attributes(blocks.get(1).body());
        Map<String, String> interp = HclBlockScanner.attributes(blocks.get(3).body());

        assertThat(HclBlockScanner.unquote(name.get("description"))).isEqualTo("Bucket name, may contain \"quotes\"");
        assertThat(name.get("default")).isEqualTo("\"acme-${var.env}-logs\"");
        assertThat(interp.get("default")).isEqualTo("\"${lookup(var.m, \"k\")} and %{ if true }x%{ endif }\"");
    }

    @Test
    void unquoteLeavesNonStringExpressionsAlone() {
        assertThat(HclBlockScanner.unquote("list(string)")).isEqualTo("list(string)");
        assertThat(HclBlockScanner.unquote(null)).isNull();
        assertThat(HclBlockScanner.unquote("\"a\\\\b\"")).isEqualTo("a\\b");
    }
}
