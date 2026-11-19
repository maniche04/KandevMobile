// Verifies encrypt() against the worked example in RFC 8291, Appendix A.
import crypto from "node:crypto";
import assert from "node:assert/strict";
import { encrypt } from "./webpush.mjs";

const ecdh = crypto.createECDH("prime256v1");
ecdh.setPrivateKey(Buffer.from("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw", "base64url"));
const body = encrypt(
  { p256dh: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4", auth: "BTBZMqHH6r4Tts7J_aSIgg" },
  Buffer.from("When I grow up, I want to be a watermelon"),
  { ecdh, salt: Buffer.from("DGv6ra1nlYgDCS1FRnbzlw", "base64url") },
);
assert.equal(body.toString("base64url"),
  "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
console.log("RFC 8291 test vector: OK");
