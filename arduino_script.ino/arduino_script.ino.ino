void setup() {
  Serial.begin(500000);
  // analogReference(INTERNAL);
}

void loop() {
  // Print the raw value from the analog pin
  Serial.println(analogRead(A5));
}
