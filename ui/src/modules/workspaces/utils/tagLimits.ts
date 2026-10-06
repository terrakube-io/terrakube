// The API stores tag keys in tag.name (varchar 128) and tag values in workspacetag.tag_value (varchar 256).
// The Terraform-compatible endpoints answer 400 for anything longer, so the inputs stop there too.
export const TAG_KEY_MAX_LENGTH = 128;
export const TAG_VALUE_MAX_LENGTH = 256;
